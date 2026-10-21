package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"runtime/debug"
	"sync"
	"sync/atomic"
	"time"

	"github.com/hashicorp/yamux"
)

type Client struct {
	upstream string
	slots    []*slot

	up, down atomic.Int64
}

type slot struct {
	c *Client

	mu         sync.Mutex
	sess       *yamux.Session
	connecting chan struct{}
	backoff    time.Duration
	nextTry    time.Time
	lastErr    string

	ready    atomic.Bool
	rttMs    atomic.Int64
	sessions atomic.Int64
	load     atomic.Int64
}

func NewClient(upstream string, sessions int) *Client {
	if sessions < 1 {
		sessions = 1
	}
	c := &Client{upstream: upstream}
	for i := 0; i < sessions; i++ {
		c.slots = append(c.slots, &slot{c: c})
	}
	return c
}

var errNoSession = errors.New("outproxy session is not available yet")

func (s *slot) setErr(err error) {
	s.mu.Lock()
	s.lastErr = err.Error()
	s.mu.Unlock()
}

func (s *slot) live() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sess != nil && !s.sess.IsClosed()
}

func (s *slot) session(ctx context.Context) (*yamux.Session, error) {
	s.mu.Lock()
	if s.sess != nil && !s.sess.IsClosed() {
		sess := s.sess
		s.mu.Unlock()
		return sess, nil
	}
	ch := s.connecting
	if ch == nil {
		ch = make(chan struct{})
		s.connecting = ch
		go s.connect(ch, time.Until(s.nextTry))
	}
	s.mu.Unlock()

	select {
	case <-ch:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sess == nil || s.sess.IsClosed() {
		if s.lastErr != "" {
			return nil, fmt.Errorf("%w: %s", errNoSession, s.lastErr)
		}
		return nil, errNoSession
	}
	return s.sess, nil
}

func (s *slot) connect(done chan struct{}, wait time.Duration) {
	if wait > 0 {
		time.Sleep(wait)
	}
	var sess *yamux.Session
	conn, err := net.DialTimeout("tcp", s.c.upstream, 5*time.Second)
	if err == nil {
		lowLatency(conn)
		if _, err = io.WriteString(conn, preamble); err == nil {
			sess, err = yamux.Client(conn, yamuxConfig())
		}
		if err != nil {
			conn.Close()
		}
	}

	s.mu.Lock()
	if err != nil {
		s.lastErr = "local tunnel: " + err.Error()
		if s.backoff == 0 {
			s.backoff = 500 * time.Millisecond
		} else if s.backoff < 10*time.Second {
			s.backoff *= 2
		}
		s.nextTry = time.Now().Add(s.backoff)
	} else {
		s.sess = sess
		s.sessions.Add(1)
		s.nextTry = time.Now().Add(2 * time.Second)
	}
	s.connecting = nil
	s.mu.Unlock()
	close(done)

	if sess != nil {
		go func() {
			<-sess.CloseChan()
			s.ready.Store(false)
		}()
	}
}

func (c *Client) pick() *slot {
	var best *slot
	for _, s := range c.slots {
		if !s.ready.Load() || !s.live() {
			continue
		}
		if best == nil || s.load.Load() < best.load.Load() {
			best = s
		}
	}
	if best == nil {
		for _, s := range c.slots {
			if s.live() {
				return s
			}
		}
		return c.slots[0]
	}
	return best
}

func (c *Client) open(ctx context.Context, cmd byte, a Addr) (*yamux.Stream, *slot, error) {
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		sl := c.pick()
		s, err := sl.session(ctx)
		if err != nil {
			return nil, nil, err
		}
		st, err := s.OpenStream()
		if err != nil {
			lastErr = err
			s.Close()
			continue
		}
		if err := writeRequest(st, cmd, a); err != nil {
			st.Close()
			lastErr = err
			continue
		}
		sl.load.Add(16 << 10)
		return st, sl, nil
	}
	return nil, nil, lastErr
}

func (c *Client) DialTCP(ctx context.Context, a Addr) (net.Conn, error) {
	st, sl, err := c.open(ctx, cmdTCP, a)
	if err != nil {
		return nil, err
	}
	return &tcpConn{Stream: st, s: sl}, nil
}

func (c *Client) DialUDP(ctx context.Context, a Addr, dst net.Addr) (*udpConn, error) {
	st, sl, err := c.open(ctx, cmdUDP, a)
	if err != nil {
		return nil, err
	}
	return &udpConn{st: st, s: sl, dst: dst}, nil
}

func (s *slot) count(up, down int) {
	s.load.Add(int64(up + down))
	s.c.up.Add(int64(up))
	s.c.down.Add(int64(down))
}

func (c *Client) Run(ctx context.Context) {
	for _, s := range c.slots {
		go s.run(ctx)
	}
	t := time.NewTicker(time.Second)
	defer t.Stop()
	var idle idleReleaser
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			for _, s := range c.slots {
				s.load.Store(s.load.Load() / 2)
			}
			idle.tick(c.up.Load() + c.down.Load())
		}
	}
}

type idleReleaser struct {
	last  int64
	quiet int
	busy  bool
}

const (
	idleReleaseAfter = 10
	idleRate         = 32 << 10
	busyRate         = 256 << 10
)

func (r *idleReleaser) tick(total int64) {
	rate := total - r.last
	r.last = total
	switch {
	case rate >= busyRate:
		r.busy, r.quiet = true, 0
	case rate > idleRate:
		r.quiet = 0
	case r.busy:
		if r.quiet++; r.quiet >= idleReleaseAfter {
			debug.FreeOSMemory()
			r.busy = false
		}
	}
}

func (s *slot) run(ctx context.Context) {
	t := time.NewTimer(0)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			s.mu.Lock()
			if s.sess != nil {
				s.sess.Close()
			}
			s.mu.Unlock()
			return
		case <-t.C:
		}
		next := 15 * time.Second
		sctx, cancel := context.WithTimeout(ctx, 20*time.Second)
		sess, err := s.session(sctx)
		cancel()
		if err == nil {
			var rtt time.Duration
			rtt, err = sess.Ping()
			if err == nil {
				s.rttMs.Store(rtt.Milliseconds())
				s.ready.Store(true)
				s.mu.Lock()
				s.backoff, s.lastErr = 0, ""
				s.mu.Unlock()
			} else {
				s.setErr(fmt.Errorf("outproxy did not answer: %w", err))
			}
		}
		if err != nil {
			s.ready.Store(false)
			next = 2 * time.Second
		}
		t.Reset(next)
	}
}

type Status struct {
	State    string `json:"state"`
	RttMs    int64  `json:"rtt_ms"`
	Up       int64  `json:"up"`
	Down     int64  `json:"down"`
	Streams  int    `json:"streams"`
	Sessions int64  `json:"sessions"`
	Ready    int    `json:"ready_sessions"`
	Error    string `json:"error,omitempty"`
}

func (c *Client) Status() Status {
	st := Status{Up: c.up.Load(), Down: c.down.Load(), State: "connecting"}
	anyLive := false
	for _, s := range c.slots {
		st.Sessions += s.sessions.Load()
		s.mu.Lock()
		live := s.sess != nil && !s.sess.IsClosed()
		if live {
			anyLive = true
			st.Streams += s.sess.NumStreams()
		}
		if st.Error == "" {
			st.Error = s.lastErr
		}
		s.mu.Unlock()
		if live && s.ready.Load() {
			st.Ready++
			if rtt := s.rttMs.Load(); st.RttMs == 0 || rtt < st.RttMs {
				st.RttMs = rtt
			}
		}
	}
	switch {
	case st.Ready > 0:
		st.State = "ready"
		st.Error = ""
	case anyLive:
		st.State = "handshake"
	}
	return st
}

type statusReader struct {
	once sync.Once
	err  error
}

func (s *statusReader) check(r io.Reader) error {
	s.once.Do(func() {
		var b [1]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			s.err = err
		} else if b[0] != statusOK {
			s.err = statusError(b[0])
		}
	})
	return s.err
}

type tcpConn struct {
	*yamux.Stream
	s      *slot
	status statusReader
}

func (t *tcpConn) Read(p []byte) (int, error) {
	if err := t.status.check(t.Stream); err != nil {
		return 0, err
	}
	n, err := t.Stream.Read(p)
	t.s.count(0, n)
	return n, err
}

func (t *tcpConn) Write(p []byte) (int, error) {
	n, err := t.Stream.Write(p)
	t.s.count(n, 0)
	return n, err
}

func (t *tcpConn) CloseWrite() error { return t.Stream.Close() }

func (t *tcpConn) Close() error { return t.Stream.Abort() }

type udpConn struct {
	st     *yamux.Stream
	s      *slot
	dst    net.Addr
	status statusReader
}

func (u *udpConn) ReadFrom(p []byte) (int, net.Addr, error) {
	if err := u.status.check(u.st); err != nil {
		return 0, nil, err
	}
	n, err := readFrame(u.st, p)
	u.s.count(0, n)
	return n, u.dst, err
}

func (u *udpConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	if err := writeFrame(u.st, p); err != nil {
		return 0, err
	}
	u.s.count(len(p), 0)
	return len(p), nil
}

func (u *udpConn) Close() error                       { return u.st.Abort() }
func (u *udpConn) LocalAddr() net.Addr                { return &net.UDPAddr{IP: net.IPv4zero} }
func (u *udpConn) SetDeadline(t time.Time) error      { return u.st.SetDeadline(t) }
func (u *udpConn) SetReadDeadline(t time.Time) error  { return u.st.SetReadDeadline(t) }
func (u *udpConn) SetWriteDeadline(t time.Time) error { return u.st.SetWriteDeadline(t) }
