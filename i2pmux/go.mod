module github.com/f7124has/i2pd-multipath-outproxy/i2pmux

go 1.26.3

require (
	github.com/hashicorp/yamux v0.1.2
	github.com/xjasonlyu/tun2socks/v2 v2.7.0
)

require (
	github.com/google/btree v1.1.3 // indirect
	github.com/google/uuid v1.6.0 // indirect
	go.uber.org/atomic v1.11.0 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	go.uber.org/zap v1.28.0 // indirect
	golang.org/x/exp v0.0.0-20260611194520-c48552f49976 // indirect
	golang.org/x/sys v0.46.0 // indirect
	golang.org/x/time v0.15.0 // indirect
	gvisor.dev/gvisor v0.0.0-20260701204157-69c2d17aea96 // indirect
)

replace github.com/hashicorp/yamux => ./third_party/yamux
