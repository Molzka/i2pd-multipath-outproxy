package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

var version = "dev"

func usage() {
	fmt.Fprint(os.Stderr, `usage: i2pmux <command> [flags]

  server   Accept mux connections; connect directly or through -outbound-socks
  socks    Local SOCKS5 client for a remote i2pmux server
  vpn      Linux/Android TUN client for a remote i2pmux server
  sam      I2P SAM connections for agg-tunnel
  version  Print version

Command options: i2pmux server -h
SOCKS outbound:  i2pmux server -outbound-socks 127.0.0.1:10808
`)
}

func main() {
	log.SetFlags(log.LstdFlags)
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "-h", "--help", "help":
		usage()
		return
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
		os.Exit(2)
	}
	if err != nil {
		log.Fatal(err)
	}
}

func runServer(args []string) error {
	fs := flag.NewFlagSet("server", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:1081", "address agg-tunnel forwards the mux port to")
	socks := fs.String("socks", "", "also serve plain SOCKS5 on this address (legacy clients), e.g. 127.0.0.1:1080")
	outbound := fs.String("outbound-socks", os.Getenv("I2PMUX_OUTBOUND_SOCKS"), "send TCP and UDP through this no-auth SOCKS5 host:port; DNS is resolved by the proxy (env I2PMUX_OUTBOUND_SOCKS)")
	allowPrivate := fs.Bool("allow-private", false, "allow private destination IPs; with SOCKS outbound, hostname access rules belong to the proxy")
	onlyV4 := fs.Bool("ipv4-only", false, "reject literal IPv6 destinations and resolve locally to IPv4; SOCKS outbound resolves hostnames itself")
	preferV6 := fs.Bool("prefer-ipv6", false, "prefer IPv6 for direct connections")
	dialTimeout := fs.Duration("dial-timeout", 20*time.Second, "timeout for resolving and connecting to a destination")
	verbose := fs.Bool("v", false, "log failed destinations")
	fs.Parse(args)
	if fs.NArg() != 0 {
		return fmt.Errorf("unexpected server arguments: %v", fs.Args())
	}
	if *dialTimeout <= 0 {
		return fmt.Errorf("dial-timeout must be positive")
	}
	if *outbound != "" {
		host, port, err := net.SplitHostPort(*outbound)
		n, portErr := strconv.ParseUint(port, 10, 16)
		if err != nil || host == "" || strings.ContainsAny(host, "/@ \t\r\n") || portErr != nil || n == 0 {
			return fmt.Errorf("outbound-socks must be a SOCKS5 host:port, e.g. 127.0.0.1:10808")
		}
		if *outbound == *listen || *outbound == *socks {
			return fmt.Errorf("outbound-socks must not point to an i2pmux listening address")
		}
		log.Printf("SOCKS5 outbound: %s; hostname resolution and access rules are handled by the proxy", *outbound)
	}

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		return err
	}
	log.Printf("i2pmux %s server listening on %s", version, ln.Addr())
	s := &Server{
		AllowPrivate:  *allowPrivate,
		OnlyIPv4:      *onlyV4,
		PreferIPv6:    *preferV6,
		DialTimeout:   *dialTimeout,
		Verbose:       *verbose,
		OutboundSOCKS: *outbound,
	}
	if *socks != "" {
		sl, err := net.Listen("tcp", *socks)
		if err != nil {
			return err
		}
		log.Printf("plain SOCKS5 listening on %s", sl.Addr())
		s.socksListen = sl.Addr().(*net.TCPAddr)
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
