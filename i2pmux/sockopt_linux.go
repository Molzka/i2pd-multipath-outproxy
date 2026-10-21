//go:build linux

package main

import (
	"net"
	"syscall"
)

const tcpNotsentLowat = 25

func lowLatency(c net.Conn) {
	tc, ok := c.(*net.TCPConn)
	if !ok {
		return
	}
	raw, err := tc.SyscallConn()
	if err != nil {
		return
	}
	raw.Control(func(fd uintptr) {
		syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, tcpNotsentLowat, 128<<10)
	})
}
