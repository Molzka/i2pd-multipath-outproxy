//go:build !linux

package main

import "errors"

func runVPN([]string) error {
	return errors.New("vpn mode is only available on Linux/Android")
}
