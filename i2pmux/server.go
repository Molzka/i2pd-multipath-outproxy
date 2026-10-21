package main

import (
	"context"
	"errors"
	"io"
	"log"
	"net"
	"net/netip"
	"sort"
	"sync/atomic"
	"time"

	"github.com/hashicorp/yamux"
)

type Server struct {
	AllowPrivate bool
	PreferIPv6   bool
	OnlyIPv4     bool
	Verbose      bool
	DialTimeout  time.Duration

	active atomic.Int64
}

func (s *Server) Serve(ln net.Listener) error {
	for {
		conn, err := ln.Accept()
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				time.Sleep(100 * time.Millisecond)
				continue
			}
			return err
		}
		go s.handleConn(conn)
	}
}

func (s *Server) handleConn(conn net.Conn) {
	defer conn.Close()
	lowLatency(conn)
	conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	buf := make([]byte, len(preamble))
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != preamble {
		log.Printf("session from %s rejected: bad preamble", conn.RemoteAddr())
		return
	}
	conn.SetReadDeadline(time.Time{})

	sess, err := yamux.Server(conn, yamuxConfig())
	if err != nil {
		log.Printf("yamux: %v", err)
		return
	}
	defer sess.Close()
	n := s.active.Add(1)
	log.Printf("session opened (%d active)", n)
	defer func() { log.Printf("session closed (%d active)", s.active.Add(-1)) }()

	for {
		st, err := sess.AcceptStream()
		if err != nil {
			return
		}
		go s.handleStream(st)
	}
}

func (s *Server) handleStream(st *yamux.Stream) {
	defer st.Close()
	st.SetReadDeadline(time.Now().Add(60 * time.Second))
	cmd, a, err := readRequest(st)
	if err != nil {
		st.Write([]byte{statusBadRequest})
		return
	}
	st.SetReadDeadline(time.Time{})

	switch cmd {
	case cmdTCP:
		s.handleTCP(st, a)
	case cmdUDP:
		s.handleUDP(st, a)
	}
}

func isPublic(ip netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.IsValid() || ip.IsUnspecified() || ip.IsLoopback() || ip.IsPrivate() ||
		ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() ||
		ip.IsInterfaceLocalMulticast() {
		return false
	}
	for _, p := range blockedPrefixes {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}

var blockedPrefixes = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),
	netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("240.0.0.0/4"),
	netip.MustParsePrefix("64:ff9b:1::/48"),
	netip.MustParsePrefix("2001:db8::/32"),
}

func (s *Server) resolve(ctx context.Context, a Addr) ([]netip.Addr, byte) {
	var ips []netip.Addr
	if a.IP.IsValid() {
		ips = []netip.Addr{a.IP}
	} else {
		network := "ip"
		if s.OnlyIPv4 {
			network = "ip4"
		}
		res, err := net.DefaultResolver.LookupNetIP(ctx, network, a.Host)
		if err != nil || len(res) == 0 {
			return nil, statusResolve
		}
		ips = res
	}
	out := ips[:0]
	for _, ip := range ips {
		ip = ip.Unmap()
		if s.OnlyIPv4 && !ip.Is4() {
			continue
		}
		if s.AllowPrivate || isPublic(ip) {
			out = append(out, ip)
		}
	}
	if len(out) == 0 {
		return nil, statusDenied
	}
	sort.SliceStable(out, func(i, j int) bool {
		if s.PreferIPv6 {
			return out[i].Is6() && !out[j].Is6()
		}
		return out[i].Is4() && !out[j].Is4()
	})
	return out, statusOK
}

func (s *Server) DialTCP(ctx context.Context, a Addr) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, s.DialTimeout)
	defer cancel()
	ips, code := s.resolve(ctx, a)
	if code != statusOK {
		return nil, statusError(code)
	}
	var d net.Dialer
	var lastErr error
	for _, ip := range ips {
		c, err := d.DialContext(ctx, "tcp", netip.AddrPortFrom(ip, a.Port).String())
		if err == nil {
			return c, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func (s *Server) DialUDP(ctx context.Context, a Addr) (*net.UDPConn, error) {
	ctx, cancel := context.WithTimeout(ctx, s.DialTimeout)
	defer cancel()
	ips, code := s.resolve(ctx, a)
	if code != statusOK {
		return nil, statusError(code)
	}
	return net.DialUDP("udp", nil, net.UDPAddrFromAddrPort(netip.AddrPortFrom(ips[0], a.Port)))
}

func errorStatus(err error) byte {
	var se statusError
	if errors.As(err, &se) {
		return byte(se)
	}
	return statusConnect
}

func (s *Server) handleTCP(st *yamux.Stream, a Addr) {
	target, err := s.DialTCP(context.Background(), a)
	if err != nil {
		s.logf("tcp %s: %v", a, err)
		st.Write([]byte{errorStatus(err)})
		return
	}
	defer target.Close()
	if _, err := st.Write([]byte{statusOK}); err != nil {
		return
	}
	relay(st, target)
}

func (s *Server) NewDirectSocks() *SocksServer {
	return &SocksServer{
		DialTCP: s.DialTCP,
		DialUDP: func(ctx context.Context, a Addr) (PacketFlow, error) {
			c, err := s.DialUDP(ctx, a)
			if err != nil {
				return nil, err
			}
			return connectedUDP{c}, nil
		},
	}
}

type connectedUDP struct{ *net.UDPConn }

func (c connectedUDP) ReadFrom(p []byte) (int, net.Addr, error) {
	n, err := c.UDPConn.Read(p)
	return n, c.RemoteAddr(), err
}

func (c connectedUDP) WriteTo(p []byte, _ net.Addr) (int, error) { return c.UDPConn.Write(p) }

func (s *Server) handleUDP(st *yamux.Stream, a Addr) {
	conn, err := s.DialUDP(context.Background(), a)
	if err != nil {
		s.logf("udp %s: %v", a, err)
		st.Write([]byte{errorStatus(err)})
		return
	}
	defer conn.Close()
	if _, err := st.Write([]byte{statusOK}); err != nil {
		return
	}

	idle := 2 * time.Minute
	if a.Port == 53 {
		idle = 20 * time.Second
	}
	var last atomic.Int64
	touch := func() { last.Store(time.Now().UnixNano()) }
	touch()

	go func() {
		buf := make([]byte, maxUDPPayload)
		for {
			n, err := readFrame(st, buf)
			if err != nil {
				conn.Close()
				return
			}
			touch()
			conn.Write(buf[:n])
		}
	}()

	buf := make([]byte, maxUDPPayload)
	for {
		conn.SetReadDeadline(time.Now().Add(idle))
		n, err := conn.Read(buf)
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() &&
				time.Since(time.Unix(0, last.Load())) < idle {
				continue
			}
			return
		}
		touch()
		if writeFrame(st, buf[:n]) != nil {
			return
		}
	}
}

func (s *Server) logf(format string, args ...any) {
	if s.Verbose {
		log.Printf(format, args...)
	}
}

type closeWriter interface{ CloseWrite() error }

func relay(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		buf := make([]byte, 32<<10)
		io.CopyBuffer(dst, src, buf)
		if cw, ok := dst.(closeWriter); ok {
			cw.CloseWrite()
		} else if st, ok := dst.(*yamux.Stream); ok {
			st.Close()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	deadline := time.Now().Add(60 * time.Second)
	a.SetReadDeadline(deadline)
	b.SetReadDeadline(deadline)
	<-done
}
