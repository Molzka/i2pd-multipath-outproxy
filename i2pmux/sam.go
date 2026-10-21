package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type samLinks struct {
	addr    string
	dest    string
	options string
	prefix  string

	ready   []atomic.Bool
	ids     []atomic.Value
	streams atomic.Int64

	mu      sync.Mutex
	lastErr string
	samOK   bool
}

var errNotReady = errors.New("link session not ready")

func (s *samLinks) setErr(err string) {
	s.mu.Lock()
	s.lastErr = err
	s.mu.Unlock()
}

func samDial(ctx context.Context, addr string) (net.Conn, *bufio.Reader, error) {
	var d net.Dialer
	c, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, nil, err
	}
	lowLatency(c)
	r := bufio.NewReader(c)
	c.SetDeadline(time.Now().Add(15 * time.Second))
	if _, err := io.WriteString(c, "HELLO VERSION MIN=3.1 MAX=3.3\n"); err != nil {
		c.Close()
		return nil, nil, err
	}
	line, err := r.ReadString('\n')
	if err != nil {
		c.Close()
		return nil, nil, fmt.Errorf("SAM handshake: %w", err)
	}
	if !strings.Contains(line, "RESULT=OK") {
		c.Close()
		return nil, nil, fmt.Errorf("SAM handshake: %s", strings.TrimSpace(line))
	}
	c.SetDeadline(time.Time{})
	return c, r, nil
}

func samResult(line string) (ok bool, msg string) {
	line = strings.TrimSpace(line)
	if strings.Contains(line, "RESULT=OK") {
		return true, ""
	}
	if i := strings.Index(line, "MESSAGE="); i >= 0 {
		return false, strings.Trim(line[i+len("MESSAGE="):], "\"")
	}
	if i := strings.Index(line, "RESULT="); i >= 0 {
		return false, line[i+len("RESULT="):]
	}
	return false, line
}

func (s *samLinks) session(ctx context.Context, i int) {
	backoff := time.Second
	for gen := 0; ctx.Err() == nil; gen++ {
		id := fmt.Sprintf("%s-%d-%d", s.prefix, i, gen)
		err := s.runSession(ctx, i, id)
		s.ready[i].Store(false)
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			s.setErr(err.Error())
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 15*time.Second {
			backoff *= 2
		}
	}
}

func (s *samLinks) runSession(ctx context.Context, i int, id string) error {
	c, r, err := samDial(ctx, s.addr)
	if err != nil {
		s.mu.Lock()
		s.samOK = false
		s.mu.Unlock()
		return err
	}
	defer c.Close()
	s.mu.Lock()
	s.samOK = true
	s.mu.Unlock()
	go func() { <-ctx.Done(); c.Close() }()

	cmd := fmt.Sprintf("SESSION CREATE STYLE=STREAM ID=%s DESTINATION=TRANSIENT SIGNATURE_TYPE=7 %s\n", id, s.options)
	if _, err := io.WriteString(c, cmd); err != nil {
		return err
	}
	c.SetReadDeadline(time.Now().Add(10 * time.Minute))
	line, err := r.ReadString('\n')
	if err != nil {
		return fmt.Errorf("SAM session: %w", err)
	}
	if ok, msg := samResult(line); !ok {
		return fmt.Errorf("SAM session: %s", msg)
	}
	c.SetReadDeadline(time.Time{})
	s.ready[i].Store(true)
	s.ids[i].Store(id)
	_, err = io.Copy(io.Discard, r)
	if err == nil {
		err = errors.New("SAM session closed by the router")
	}
	return err
}

type bufConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufConn) Read(p []byte) (int, error) { return b.r.Read(p) }

func (b *bufConn) CloseWrite() error {
	if cw, ok := b.Conn.(closeWriter); ok {
		return cw.CloseWrite()
	}
	return nil
}

func (s *samLinks) connect(ctx context.Context, i int) (net.Conn, error) {
	v := s.ids[i].Load()
	if v == nil || !s.ready[i].Load() {
		return nil, errNotReady
	}
	c, r, err := samDial(ctx, s.addr)
	if err != nil {
		return nil, err
	}
	cmd := fmt.Sprintf("STREAM CONNECT ID=%s DESTINATION=%s SILENT=false\n", v.(string), s.dest)
	if _, err := io.WriteString(c, cmd); err != nil {
		c.Close()
		return nil, err
	}
	c.SetReadDeadline(time.Now().Add(2 * time.Minute))
	line, err := r.ReadString('\n')
	if err != nil {
		c.Close()
		return nil, fmt.Errorf("SAM connect: %w", err)
	}
	if ok, msg := samResult(line); !ok {
		c.Close()
		return nil, fmt.Errorf("outproxy: %s", msg)
	}
	c.SetReadDeadline(time.Time{})
	return &bufConn{Conn: c, r: r}, nil
}

func (s *samLinks) serve(ctx context.Context, i int, ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			time.Sleep(100 * time.Millisecond)
			continue
		}
		go func() {
			defer conn.Close()
			lowLatency(conn)
			remote, err := s.connect(ctx, i)
			if err != nil {
				if !errors.Is(err, errNotReady) {
					s.setErr(err.Error())
				}
				return
			}
			defer remote.Close()
			s.streams.Add(1)
			defer s.streams.Add(-1)
			s.setErr("")
			relay(conn, remote)
		}()
	}
}

type samStatus struct {
	SAM     string `json:"sam"`
	Ready   int    `json:"ready"`
	Links   int    `json:"links"`
	Streams int64  `json:"streams"`
	Error   string `json:"error,omitempty"`
}

func (s *samLinks) status() samStatus {
	st := samStatus{Links: len(s.ready), Streams: s.streams.Load()}
	for i := range s.ready {
		if s.ready[i].Load() {
			st.Ready++
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	st.SAM = "unreachable"
	if s.samOK {
		st.SAM = "ok"
	}
	st.Error = s.lastErr
	return st
}

func samOptions(hops, quantity int) string {
	variance := 0
	if hops > 1 {
		variance = -1
	}
	opts := []string{
		"inbound.length=" + strconv.Itoa(hops),
		"outbound.length=" + strconv.Itoa(hops),
		"inbound.lengthVariance=" + strconv.Itoa(variance),
		"outbound.lengthVariance=" + strconv.Itoa(variance),
		"inbound.quantity=" + strconv.Itoa(quantity+1),
		"outbound.quantity=" + strconv.Itoa(quantity),
		"i2cp.dontPublishLeaseSet=true",
		"i2p.streaming.maxResends=3",
		"i2p.streaming.initialAckDelay=200",
		"i2p.streaming.maxWindowSize=512",
		"i2p.streaming.answerPings=true",
		"crypto.ratchet.inboundTags=2400",
	}
	return strings.Join(opts, " ")
}

func runSAM(args []string) error {
	fs := flag.NewFlagSet("sam", flag.ExitOnError)
	addr := fs.String("sam", "127.0.0.1:7656", "SAM bridge of the running I2P router")
	dest := fs.String("dest", "", "outproxy address (.b32.i2p)")
	links := fs.Int("links", 16, "number of links (I2P destinations)")
	base := fs.Int("listen-base", 3001, "first local port; link i listens on base+i")
	hops := fs.Int("hops", 3, "tunnel length on our side")
	quantity := fs.Int("quantity", 2, "tunnels per direction per link")
	interval := fs.Duration("status-interval", 2*time.Second, "status line interval (0 = off)")
	watchStdin := fs.Bool("watch-stdin", false, "exit when stdin closes (parent process died)")
	fs.Parse(args)
	if *dest == "" {
		return errors.New("-dest is required")
	}

	var rnd [4]byte
	rand.Read(rnd[:])
	s := &samLinks{
		addr:    *addr,
		dest:    *dest,
		options: samOptions(*hops, *quantity),
		prefix:  "multipath-" + hex.EncodeToString(rnd[:]),
		ready:   make([]atomic.Bool, *links),
		ids:     make([]atomic.Value, *links),
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	for i := 0; i < *links; i++ {
		ln, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(*base+i)))
		if err != nil {
			return err
		}
		defer ln.Close()
		go s.serve(ctx, i, ln)
		go s.session(ctx, i)
	}
	log.Printf("i2pmux %s: %d SAM links via %s -> %s", version, *links, *addr, *dest)

	stop := make(chan struct{})
	if *watchStdin {
		go func() {
			io.Copy(io.Discard, os.Stdin)
			close(stop)
		}()
	}
	var tick <-chan time.Time
	if *interval > 0 {
		t := time.NewTicker(*interval)
		defer t.Stop()
		tick = t.C
	}
	enc := json.NewEncoder(os.Stdout)
	for {
		select {
		case <-tick:
			enc.Encode(s.status())
		case <-stop:
			return nil
		}
	}
}
