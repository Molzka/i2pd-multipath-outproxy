package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"time"

	"github.com/hashicorp/yamux"
)

const preamble = "I2PMUX/1\n"

const (
	cmdTCP byte = 'T'
	cmdUDP byte = 'U'
)

const (
	statusOK byte = iota
	statusDenied
	statusResolve
	statusConnect
	statusBadRequest
)

var statusText = map[byte]string{
	statusDenied:     "destination not allowed",
	statusResolve:    "cannot resolve host",
	statusConnect:    "connection failed",
	statusBadRequest: "bad request",
}

type statusError byte

func (e statusError) Error() string {
	if s, ok := statusText[byte(e)]; ok {
		return "outproxy: " + s
	}
	return fmt.Sprintf("outproxy: status %d", byte(e))
}

const (
	atypIPv4   = 1
	atypDomain = 3
	atypIPv6   = 4
)

type Addr struct {
	Host string
	IP   netip.Addr
	Port uint16
}

func (a Addr) String() string {
	if a.IP.IsValid() {
		return netip.AddrPortFrom(a.IP, a.Port).String()
	}
	return net.JoinHostPort(a.Host, strconv.Itoa(int(a.Port)))
}

func addrFromHostPort(host string, port uint16) Addr {
	if ip, err := netip.ParseAddr(host); err == nil {
		return Addr{IP: ip.Unmap(), Port: port}
	}
	return Addr{Host: host, Port: port}
}

func appendAddr(b []byte, a Addr) ([]byte, error) {
	switch {
	case a.IP.Is4():
		b = append(b, atypIPv4)
		ip := a.IP.As4()
		b = append(b, ip[:]...)
	case a.IP.Is6():
		b = append(b, atypIPv6)
		ip := a.IP.As16()
		b = append(b, ip[:]...)
	default:
		if len(a.Host) == 0 || len(a.Host) > 255 {
			return nil, errors.New("invalid host name")
		}
		b = append(b, atypDomain, byte(len(a.Host)))
		b = append(b, a.Host...)
	}
	return binary.BigEndian.AppendUint16(b, a.Port), nil
}

func readAddr(r io.Reader) (Addr, error) {
	var t [1]byte
	if _, err := io.ReadFull(r, t[:]); err != nil {
		return Addr{}, err
	}
	var a Addr
	switch t[0] {
	case atypIPv4:
		var ip [4]byte
		if _, err := io.ReadFull(r, ip[:]); err != nil {
			return a, err
		}
		a.IP = netip.AddrFrom4(ip)
	case atypIPv6:
		var ip [16]byte
		if _, err := io.ReadFull(r, ip[:]); err != nil {
			return a, err
		}
		a.IP = netip.AddrFrom16(ip).Unmap()
	case atypDomain:
		var l [1]byte
		if _, err := io.ReadFull(r, l[:]); err != nil {
			return a, err
		}
		host := make([]byte, l[0])
		if _, err := io.ReadFull(r, host); err != nil {
			return a, err
		}
		a.Host = string(host)
		if ip, err := netip.ParseAddr(a.Host); err == nil {
			a.Host, a.IP = "", ip.Unmap()
		}
	default:
		return a, fmt.Errorf("unknown address type %d", t[0])
	}
	var p [2]byte
	if _, err := io.ReadFull(r, p[:]); err != nil {
		return a, err
	}
	a.Port = binary.BigEndian.Uint16(p[:])
	return a, nil
}

func writeRequest(w io.Writer, cmd byte, a Addr) error {
	b, err := appendAddr([]byte{cmd}, a)
	if err != nil {
		return err
	}
	_, err = w.Write(b)
	return err
}

func readRequest(r io.Reader) (byte, Addr, error) {
	var c [1]byte
	if _, err := io.ReadFull(r, c[:]); err != nil {
		return 0, Addr{}, err
	}
	if c[0] != cmdTCP && c[0] != cmdUDP {
		return 0, Addr{}, fmt.Errorf("unknown command %q", c[0])
	}
	a, err := readAddr(r)
	return c[0], a, err
}

const maxUDPPayload = 65535

func writeFrame(w io.Writer, p []byte) error {
	if len(p) > maxUDPPayload {
		return errors.New("datagram too large")
	}
	b := make([]byte, 2+len(p))
	binary.BigEndian.PutUint16(b, uint16(len(p)))
	copy(b[2:], p)
	_, err := w.Write(b)
	return err
}

func readFrame(r io.Reader, buf []byte) (int, error) {
	var h [2]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return 0, err
	}
	n := int(binary.BigEndian.Uint16(h[:]))
	if n <= len(buf) {
		_, err := io.ReadFull(r, buf[:n])
		return n, err
	}
	if _, err := io.ReadFull(r, buf); err != nil {
		return 0, err
	}
	_, err := io.CopyN(io.Discard, r, int64(n-len(buf)))
	return len(buf), err
}

const (
	streamWindowStart = 4 << 20
	streamWindowMax   = 32 << 20
)

func yamuxConfig() *yamux.Config {
	c := yamux.DefaultConfig()
	c.AcceptBacklog = 1024
	c.EnableKeepAlive = true
	c.KeepAliveInterval = 30 * time.Second
	c.ConnectionWriteTimeout = 60 * time.Second
	c.StartStreamWindowSize = streamWindowStart
	c.MaxStreamWindowSize = streamWindowMax
	c.StreamOpenTimeout = 90 * time.Second
	c.StreamCloseTimeout = 60 * time.Second
	c.LogOutput = io.Discard
	return c
}
