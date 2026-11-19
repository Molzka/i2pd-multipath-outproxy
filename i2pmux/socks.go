package main

import (
	"context"
	"errors"
	"io"
	"log"
	"net"
	"net/netip"
	"sync"
	"time"
)

const (
	maxUDPFlows        = 256
	maxPendingUDPFlows = 8
	udpFlowQueueSize   = 8
)

type socksUDPFlow struct {
	packets chan []byte
}

type PacketFlow interface {
	ReadFrom(p []byte) (int, net.Addr, error)
	WriteTo(p []byte, addr net.Addr) (int, error)
	SetReadDeadline(t time.Time) error
	Close() error
}

type SocksServer struct {
	DialTCP func(ctx context.Context, a Addr) (net.Conn, error)
	DialUDP func(ctx context.Context, a Addr) (PacketFlow, error)
}

func NewMuxSocks(c *Client) *SocksServer {
	return &SocksServer{
		DialTCP: c.DialTCP,
		DialUDP: func(ctx context.Context, a Addr) (PacketFlow, error) { return c.DialUDP(ctx, a, nil) },
	}
}

func (s *SocksServer) Serve(ln net.Listener) error {
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
		go func() {
			if err := s.handle(conn); err != nil && !errors.Is(err, io.EOF) {
				log.Printf("socks %s: %v", conn.RemoteAddr(), err)
			}
		}()
	}
}

func (s *SocksServer) handle(conn net.Conn) error {
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(30 * time.Second))

	var h [2]byte
	if _, err := io.ReadFull(conn, h[:]); err != nil {
		return err
	}
	if h[0] != 5 {
		return errors.New("not SOCKS5")
	}
	methods := make([]byte, h[1])
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	noAuth := false
	for _, m := range methods {
		noAuth = noAuth || m == 0
	}
	if !noAuth {
		conn.Write([]byte{5, 0xff})
		return errors.New("client requires authentication")
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return err
	}

	var req [3]byte
	if _, err := io.ReadFull(conn, req[:]); err != nil {
		return err
	}
	if req[0] != 5 || req[2] != 0 {
		return errors.New("invalid SOCKS5 request")
	}
	dst, err := readAddr(conn)
	if err != nil {
		return err
	}
	conn.SetDeadline(time.Time{})

	switch req[1] {
	case 1:
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		remote, err := s.DialTCP(ctx, dst)
		cancel()
		if err != nil {
			code := byte(1)
			var se statusError
			if errors.As(err, &se) {
				switch byte(se) {
				case statusDenied:
					code = 2
				case statusResolve:
					code = 4
				case statusConnect:
					code = 5
				}
			}
			conn.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
			return err
		}
		defer remote.Close()
		if _, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
			return err
		}
		relay(conn, remote)
		return nil
	case 3:
		return s.associate(conn, dst)
	default:
		conn.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return errors.New("unsupported command")
	}
}

func (s *SocksServer) associate(ctrl net.Conn, requested Addr) error {
	peer := ctrl.RemoteAddr().(*net.TCPAddr).AddrPort().Addr().Unmap()
	if requested.Host != "" || (requested.IP.IsValid() && !requested.IP.IsUnspecified() && requested.IP.Unmap() != peer) {
		ctrl.Write([]byte{5, 2, 0, 1, 0, 0, 0, 0, 0, 0})
		return errors.New("UDP client address differs from SOCKS connection")
	}
	local := ctrl.LocalAddr().(*net.TCPAddr)
	pc, err := net.ListenUDP("udp", &net.UDPAddr{IP: local.IP})
	if err != nil {
		ctrl.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return err
	}
	defer pc.Close()
	bound := pc.LocalAddr().(*net.UDPAddr).AddrPort()
	rep, _ := appendAddr([]byte{5, 0, 0}, Addr{IP: bound.Addr().Unmap(), Port: bound.Port()})
	if _, err := ctrl.Write(rep); err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var (
		mu      sync.Mutex
		flows   = map[string]*socksUDPFlow{}
		clientA = netip.AddrPortFrom(peer, requested.Port)
	)
	opening := make(chan struct{}, maxPendingUDPFlows)
	go func() {
		io.Copy(io.Discard, ctrl)
		cancel()
		pc.Close()
	}()

	buf := make([]byte, maxUDPPayload)
	for {
		n, from, err := pc.ReadFromUDPAddrPort(buf)
		if err != nil {
			return nil
		}
		from = netip.AddrPortFrom(from.Addr().Unmap(), from.Port())
		if from.Addr() != peer || n < 4 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {
			continue
		}
		r := &byteReader{b: buf[3:n]}
		dst, err := readAddr(r)
		if err != nil {
			continue
		}
		payload := r.b
		mu.Lock()
		if clientA.Port() == 0 {
			clientA = from
		}
		if from != clientA {
			mu.Unlock()
			continue
		}
		key := dst.String()
		f := flows[key]
		if f == nil {
			if len(flows) >= maxUDPFlows {
				mu.Unlock()
				continue
			}
			select {
			case opening <- struct{}{}:
			default:
				mu.Unlock()
				continue
			}
			f = &socksUDPFlow{packets: make(chan []byte, udpFlowQueueSize)}
			flows[key] = f
			header := append([]byte(nil), buf[:n-len(payload)]...)
			to := clientA
			go func(f *socksUDPFlow, key string, dst Addr, header []byte) {
				defer func() {
					mu.Lock()
					if flows[key] == f {
						delete(flows, key)
					}
					mu.Unlock()
				}()
				flowCtx, stopFlow := context.WithCancel(ctx)
				defer stopFlow()
				dialCtx, stopDial := context.WithTimeout(flowCtx, 10*time.Second)
				remote, err := s.DialUDP(dialCtx, dst)
				stopDial()
				<-opening
				if err != nil {
					return
				}
				defer remote.Close()
				stopClose := context.AfterFunc(flowCtx, func() { remote.Close() })
				defer stopClose()
				go func() {
					defer stopFlow()
					rb := make([]byte, maxUDPPayload)
					for {
						remote.SetReadDeadline(time.Now().Add(2 * time.Minute))
						m, _, err := remote.ReadFrom(rb)
						if err != nil {
							return
						}
						out := append(append([]byte(nil), header...), rb[:m]...)
						if _, err := pc.WriteToUDPAddrPort(out, to); err != nil {
							return
						}
					}
				}()
				for {
					select {
					case <-flowCtx.Done():
						return
					case payload := <-f.packets:
						if flowCtx.Err() != nil {
							return
						}
						if _, err := remote.WriteTo(payload, nil); err != nil {
							return
						}
					}
				}
			}(f, key, dst, header)
		}
		mu.Unlock()
		// A slow destination may drop UDP packets, but cannot delay other flows.
		if len(f.packets) < cap(f.packets) {
			f.packets <- append([]byte(nil), payload...)
		}
	}
}

type byteReader struct{ b []byte }

func (r *byteReader) Read(p []byte) (int, error) {
	if len(r.b) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.b)
	r.b = r.b[n:]
	return n, nil
}
