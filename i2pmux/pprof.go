//go:build pprof

package main

import (
	"log"
	"net/http"
	_ "net/http/pprof"
	"os"
)

func init() {
	addr := os.Getenv("I2PMUX_PPROF")
	if addr == "" {
		addr = "127.0.0.1:6062"
	}
	go func() { log.Println(http.ListenAndServe(addr, nil)) }()
}
