//go:build linux

package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	tlog "github.com/xjasonlyu/tun2socks/v2/log"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
)

type muxProxy struct{ c *Client }

var _ proxy.Proxy = (*muxProxy)(nil)

func (p *muxProxy) DialContext(ctx context.Context, m *M.Metadata) (net.Conn, error) {
	return p.c.DialTCP(ctx, Addr{IP: m.DstIP.Unmap(), Port: m.DstPort})
}

func (p *muxProxy) DialUDP(m *M.Metadata) (net.PacketConn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return p.c.DialUDP(ctx, Addr{IP: m.DstIP.Unmap(), Port: m.DstPort}, m.UDPAddr())
}

func recvFD(path string, timeout time.Duration) (int, error) {
	os.Remove(path)
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return -1, err
	}
	defer ln.Close()
	defer os.Remove(path)
	ln.SetDeadline(time.Now().Add(timeout))
	conn, err := ln.AcceptUnix()
	if err != nil {
		return -1, err
	}
	defer conn.Close()
	b, oob := make([]byte, 1), make([]byte, syscall.CmsgSpace(4))
	_, oobn, _, _, err := conn.ReadMsgUnix(b, oob)
	if err != nil {
		return -1, err
	}
	msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil || len(msgs) == 0 {
		return -1, errors.New("no file descriptor received")
	}
	fds, err := syscall.ParseUnixRights(&msgs[0])
	if err != nil || len(fds) == 0 {
		return -1, errors.New("no file descriptor received")
	}
	conn.Write([]byte{1})
	return fds[0], nil
}

func runVPN(args []string) error {
	fs := flag.NewFlagSet("vpn", flag.ExitOnError)
	fdSock := fs.String("fd-sock", "", "unix socket path to receive the TUN file descriptor on")
	fdNum := fs.Int("fd", -1, "already open TUN file descriptor (alternative to -fd-sock)")
	upstream := fs.String("upstream", "127.0.0.1:1090", "local agg-tunnel port of the mux service")
	mtu := fs.Uint("mtu", 1500, "TUN MTU (must match VpnService.Builder.setMtu)")
	interval := fs.Duration("status-interval", 2*time.Second, "status line interval")
	logLevel := fs.String("log-level", "error", "tun2socks log level")
	sessions := fs.Int("sessions", 3, "parallel sessions (aggregated connections)")
	fs.Parse(args)

	if lvl, err := tlog.ParseLevel(*logLevel); err == nil {
		if l, err := tlog.NewLeveled(lvl); err == nil {
			tlog.SetLogger(l)
		}
	}

	fd := *fdNum
	if fd < 0 {
		if *fdSock == "" {
			return errors.New("either -fd or -fd-sock is required")
		}
		var err error
		if fd, err = recvFD(*fdSock, 30*time.Second); err != nil {
			return fmt.Errorf("receive tun fd: %w", err)
		}
	}

	if err := syscall.SetNonblock(fd, true); err != nil {
		return fmt.Errorf("set nonblock: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	client := NewClient(*upstream, *sessions)
	go client.Run(ctx)

	tunnel.T().SetProxy(&muxProxy{c: client})
	tunnel.T().SetUDPTimeout(60 * time.Second)

	dev, err := fdbased.Open(strconv.Itoa(fd), uint32(*mtu), 0)
	if err != nil {
		return fmt.Errorf("open tun: %w", err)
	}
	stack, err := core.CreateStack(&core.Config{LinkEndpoint: dev, TransportHandler: tunnel.T()})
	if err != nil {
		dev.Close()
		return fmt.Errorf("create stack: %w", err)
	}

	stop := make(chan struct{})
	go func() {
		io.Copy(io.Discard, os.Stdin)
		close(stop)
	}()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)

	enc := json.NewEncoder(os.Stdout)
	t := time.NewTicker(*interval)
	defer t.Stop()
	enc.Encode(client.Status())
loop:
	for {
		select {
		case <-t.C:
			if enc.Encode(client.Status()) != nil {
				break loop
			}
		case <-stop:
			break loop
		case <-sig:
			break loop
		}
	}
	cancel()
	stack.Close()
	dev.Close()
	return nil
}
