package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"time"
)

var version = "dev"

func usage() {
	fmt.Fprintf(os.Stderr, "usage: i2pmux server|socks|vpn|sam|version [flags]\n")
	os.Exit(2)
}

func main() {
	log.SetFlags(log.LstdFlags)
	if len(os.Args) < 2 {
		usage()
	}
	var err error
	switch os.Args[1] {
	case "server":
		err = runServer(os.Args[2:])
	case "socks":
		err = runSocks(os.Args[2:])
	case "vpn":
		err = runVPN(os.Args[2:])
	case "sam":
		err = runSAM(os.Args[2:])
	case "version":
		fmt.Println(version)
	default:
		usage()
	}
	if err != nil {
		log.Fatal(err)
	}
}

func runServer(args []string) error {
	fs := flag.NewFlagSet("server", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:1081", "address agg-tunnel forwards the mux port to")
	socks := fs.String("socks", "", "also serve plain SOCKS5 on this address (legacy clients), e.g. 127.0.0.1:1080")
	allowPrivate := fs.Bool("allow-private", false, "allow connections to loopback/private/link-local addresses")
	onlyV4 := fs.Bool("ipv4-only", false, "never connect to IPv6 destinations")
	preferV6 := fs.Bool("prefer-ipv6", false, "try IPv6 addresses before IPv4")
	dialTimeout := fs.Duration("dial-timeout", 20*time.Second, "timeout for resolving and connecting to a destination")
	verbose := fs.Bool("v", false, "log failed destinations")
	fs.Parse(args)

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		return err
	}
	log.Printf("i2pmux %s server listening on %s", version, ln.Addr())
	s := &Server{
		AllowPrivate: *allowPrivate,
		OnlyIPv4:     *onlyV4,
		PreferIPv6:   *preferV6,
		DialTimeout:  *dialTimeout,
		Verbose:      *verbose,
	}
	if *socks != "" {
		sl, err := net.Listen("tcp", *socks)
		if err != nil {
			return err
		}
		log.Printf("plain SOCKS5 listening on %s", sl.Addr())
		go func() { log.Fatal(s.NewDirectSocks().Serve(sl)) }()
	}
	return s.Serve(ln)
}

func runSocks(args []string) error {
	fs := flag.NewFlagSet("socks", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:1081", "SOCKS5 listen address")
	upstream := fs.String("upstream", "127.0.0.1:1090", "local agg-tunnel port of the mux service")
	statusEvery := fs.Duration("status-interval", time.Minute, "log session status this often (0 = never)")
	sessions := fs.Int("sessions", 3, "parallel sessions (aggregated connections)")
	fs.Parse(args)

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		return err
	}
	c := NewClient(*upstream, *sessions)
	go c.Run(context.Background())
	if *statusEvery > 0 {
		go func() {
			for range time.Tick(*statusEvery) {
				b, _ := json.Marshal(c.Status())
				log.Printf("status %s", b)
			}
		}()
	}
	log.Printf("i2pmux %s SOCKS5 on %s -> %s", version, ln.Addr(), *upstream)
	return NewMuxSocks(c).Serve(ln)
}
