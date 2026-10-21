//go:build !linux

package main

import "net"

func lowLatency(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		tc.SetWriteBuffer(256 << 10)
	}
}
