package main

import (
	"context"
	"net/netip"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	tlog "github.com/xjasonlyu/tun2socks/v2/log"
)

// TCP uses the mux connection budget instead of tun2socks' fixed five seconds.
// UDP keeps the existing tun2socks NAT and timeout handling.
type vpnTransport struct {
	ctx    context.Context
	client *Client
	udp    adapter.TransportHandler
}

func (t *vpnTransport) HandleTCP(origin adapter.TCPConn) {
	go t.forwardTCP(origin)
}

func (t *vpnTransport) HandleUDP(origin adapter.UDPConn) {
	t.udp.HandleUDP(origin)
}

func (t *vpnTransport) forwardTCP(origin adapter.TCPConn) {
	defer origin.Close()
	stopOrigin := context.AfterFunc(t.ctx, func() { origin.Close() })
	defer stopOrigin()
	id := origin.ID()
	ip, ok := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(t.ctx, 30*time.Second)
	remote, err := t.client.DialTCP(ctx, Addr{IP: ip.Unmap(), Port: id.LocalPort})
	cancel()
	if err != nil {
		tlog.Warnf("[TCP] dial %s:%d: %v", ip, id.LocalPort, err)
		return
	}
	defer remote.Close()
	stopRemote := context.AfterFunc(t.ctx, func() { remote.Close() })
	defer stopRemote()
	relay(origin, remote)
}
