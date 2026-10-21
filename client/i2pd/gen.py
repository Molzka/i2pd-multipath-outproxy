#!/usr/bin/env python3
"""python3 gen.py <server>.b32.i2p [--links 32] [--hops 4] > data/tunnels.conf"""
import argparse
import re
import sys

TEMPLATE = """[o{i}]
type = client
address = 127.0.0.1
port = {port}
destination = {dest}
destinationport = 1337
keys = transient-o{i}.dat
inbound.quantity = 3
outbound.quantity = 2
inbound.length = {hops}
outbound.length = {hops}
inbound.lengthVariance = {variance}
outbound.lengthVariance = {variance}
i2p.streaming.maxResends = 3
i2p.streaming.initialAckDelay = 200
i2p.streaming.maxWindowSize = 512
i2p.streaming.answerPings = true
crypto.ratchet.inboundTags = 2400
"""


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("destination", help="server address, xxxx.b32.i2p (see server/OUTPROXY_ADDRESS.txt)")
    p.add_argument("--links", type=int, default=32, help="number of tunnels/links (default 32)")
    p.add_argument("--hops", type=int, default=4, choices=range(1, 8), metavar="1-7",
                   help="hops on the client side (default 4, with -1 variance: 3-4)")
    p.add_argument("--first-port", type=int, default=3001)
    a = p.parse_args()

    if not re.fullmatch(r"[a-z2-7]{52,}\.b32\.i2p", a.destination):
        sys.exit(f"not a .b32.i2p address: {a.destination}")

    variance = -1 if a.hops > 1 else 0
    for i in range(1, a.links + 1):
        print(TEMPLATE.format(i=i, port=a.first_port + i - 1, dest=a.destination,
                              hops=a.hops, variance=variance))


if __name__ == "__main__":
    main()
