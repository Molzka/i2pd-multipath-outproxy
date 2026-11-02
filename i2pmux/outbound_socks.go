package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"
)

func (s *Server) checkProxyDestination(a Addr) error {
	if a.Port == 0 || (!a.IP.IsValid() && (len(a.Host) == 0 || len(a.Host) > 255)) {
		return statusError(statusBadRequest)
	}
	if a.IP.IsValid() && ((s.OnlyIPv4 && !a.IP.Unmap().Is4()) || (!s.AllowPrivate && !isPublic(a.IP))) {
		return statusError(statusDenied)
	}
	return nil
}

func (s *Server) socksRequest(ctx context.Context, command byte, target Addr) (conn net.Conn, bound Addr, err error) {
	request, err := appendAddr([]byte{5, command, 0}, target)
	if err != nil {
		return nil, bound, err
	}
	var dialer net.Dialer
	conn, err = dialer.DialContext(ctx, "tcp", s.OutboundSOCKS)
	if err != nil {
		return nil, bound, err
	}
	if s.socksListen != nil {
		peer := conn.RemoteAddr().(*net.TCPAddr)
		if peer.Port == s.socksListen.Port && (s.socksListen.IP.IsUnspecified() || s.socksListen.IP.Equal(peer.IP)) {
			conn.Close()
			return nil, bound, errors.New("outbound SOCKS5 points back to the server's own SOCKS listener")
		}
	}
	socket := conn
	stop := context.AfterFunc(ctx, func() { socket.Close() })
	defer func() {
		stop()
		if err == nil {
			err = ctx.Err()
		}
		if err != nil {
			conn.Close()
			conn = nil
		}
	}()
	if deadline, ok := ctx.Deadline(); ok {
		if err = conn.SetDeadline(deadline); err != nil {
			return
		}
	}
	if _, err = conn.Write([]byte{5, 1, 0}); err != nil {
		return
	}
	var method [2]byte
	if _, err = io.ReadFull(conn, method[:]); err != nil {
		return
	}
	if method != [2]byte{5, 0} {
		err = errors.New("outbound SOCKS5 proxy must accept no-auth authentication")
		return
	}
	if _, err = conn.Write(request); err != nil {
		return
	}
	var reply [3]byte
	if _, err = io.ReadFull(conn, reply[:]); err != nil {
		return
	}
	if reply[0] != 5 || reply[2] != 0 {
		err = errors.New("invalid SOCKS5 reply")
		return
	}
	if reply[1] != 0 {
		err = fmt.Errorf("SOCKS5 request rejected (reply %d)", reply[1])
		return
	}
	if bound, err = readAddr(conn); err != nil {
		return
	}
	if !bound.IP.IsValid() && bound.Host == "" {
		err = errors.New("SOCKS5 proxy returned an empty address")
		return
	}
	err = conn.SetDeadline(time.Time{})
	return
}

func (s *Server) dialSOCKSUDP(ctx context.Context, target Addr) (net.Conn, error) {
	header, err := appendAddr([]byte{0, 0, 0}, target)
	if err != nil {
		return nil, err
	}
	control, relay, err := s.socksRequest(ctx, 3, Addr{IP: netip.IPv4Unspecified()})
	if err != nil {
		return nil, err
	}
	if relay.Port == 0 {
		control.Close()
		return nil, errors.New("SOCKS5 proxy returned an empty UDP relay port")
	}
	if relay.IP.IsValid() && relay.IP.IsUnspecified() {
		relay.IP = control.RemoteAddr().(*net.TCPAddr).AddrPort().Addr().Unmap()
	}
	var dialer net.Dialer
	packet, err := dialer.DialContext(ctx, "udp", relay.String())
	if err != nil {
		control.Close()
		return nil, err
	}
	conn := &socksUDPConn{UDPConn: packet.(*net.UDPConn), control: control, header: header, target: target}
	go func() {
		io.Copy(io.Discard, control)
		conn.Close()
	}()
	return conn, nil
}

type socksUDPConn struct {
	*net.UDPConn
	control net.Conn
	header  []byte
	target  Addr
	once    sync.Once
	readMu  sync.Mutex
	readBuf []byte
}

func (c *socksUDPConn) Close() error {
	var err error
	c.once.Do(func() {
		c.control.Close()
		err = c.UDPConn.Close()
	})
	return err
}

func (c *socksUDPConn) Write(p []byte) (int, error) {
	if len(c.header)+len(p) > 65507 {
		return 0, errors.New("UDP packet is too large for SOCKS5")
	}
	packet := make([]byte, len(c.header)+len(p))
	copy(packet, c.header)
	copy(packet[len(c.header):], p)
	n, err := c.UDPConn.Write(packet)
	if err != nil {
		return 0, err
	}
	if n != len(packet) {
		return 0, io.ErrShortWrite
	}
	return len(p), nil
}

func (c *socksUDPConn) Read(p []byte) (int, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()
	if c.readBuf == nil {
		c.readBuf = make([]byte, maxUDPPayload)
	}
	packet := c.readBuf
	for {
		n, err := c.UDPConn.Read(packet)
		if err != nil {
			return 0, err
		}
		if n < 4 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 {
			continue
		}
		r := &byteReader{b: packet[3:n]}
		source, err := readAddr(r)
		if err != nil || source.Port != c.target.Port {
			continue
		}
		if c.target.IP.IsValid() && source.IP.Unmap() != c.target.IP.Unmap() {
			continue
		}
		if len(r.b) > len(p) {
			return 0, io.ErrShortBuffer
		}
		return copy(p, r.b), nil
	}
}
