#!/bin/sh
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERVER="$ROOT/server"
ADDRESS="$SERVER/OUTPROXY_ADDRESS.txt"

log() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }

for tool in docker curl ss; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required; install it before running this script"
done
. "$ROOT/scripts/compose.sh"
docker info >/dev/null 2>&1 || die "Docker is unavailable; check that it is running and accessible to your user"
if [ -e "$ADDRESS" ]; then
    [ -w "$ADDRESS" ] || die "$ADDRESS is not writable; change its owner before running this script"
else
    [ -w "$SERVER" ] || die "$SERVER is not writable; check the project directory owner and permissions"
fi

umask 077
ENVF="$SERVER/i2pd/.env"
if [ -e "$ENVF" ]; then
    [ -r "$ENVF" ] || die "$ENVF is not readable; check its owner and permissions"
    port=$(sed -n 's/^I2PD_PORT=\([0-9]*\)$/\1/p' "$ENVF")
    case "$port" in
        ''|*[!0-9]*) die "set I2PD_PORT to a port from 1 to 65535 in $ENVF" ;;
    esac
    [ "$port" -ge 1 ] && [ "$port" -le 65535 ] || die "I2PD_PORT must be between 1 and 65535"
else
    port=$(awk 'BEGIN { srand(); print int(10000 + rand() * 20000) }')
    printf 'I2PD_PORT=%s\n' "$port" > "$ENVF"
fi
log "i2pd transport port: $port (tcp+udp)"

log "building images"
(cd "$SERVER/i2pd" && compose build --pull)
(cd "$SERVER/multipath" && compose build --pull)

log "starting containers"
(cd "$SERVER/multipath" && compose up -d --remove-orphans)
(cd "$SERVER/i2pd" && compose up -d --remove-orphans)

sleep 3
for p in 1337 1080 1081; do
    ss -ltn "sport = :$p" | grep -q 127.0.0.1 || die "nothing listens on 127.0.0.1:$p (see: cd server/multipath && ./scripts/logs.sh)"
done
if ss -ltn | awk '{print $4}' | grep -Eq '^(0\.0\.0\.0|\[::\]|\*):(1337|1080|1081)$'; then
    die "an outproxy port is exposed on a public interface"
fi
log "local services are up (127.0.0.1:1337 agg-tunnel, :1080 SOCKS, :1081 mux)"

log "waiting for i2pd to publish the outproxy address (can take a few minutes)"
b33() {
    b32=$(curl --noproxy '*' -s --max-time 5 'http://127.0.0.1:7070/?page=i2p_tunnels' 2>/dev/null \
        | grep -oE 'local_destination&(amp;)?b32=[a-z2-7]{52}"?>multipath<' | grep -oE '[a-z2-7]{52}' | head -n1)
    [ -n "$b32" ] || return 0
    curl --noproxy '*' -s --max-time 5 "http://127.0.0.1:7070/?page=local_destination&b32=$b32" 2>/dev/null \
        | grep -oE '[a-z2-7]{55,}\.b32\.i2p' | head -n1
}
addr=""
i=0
while [ $i -lt 120 ]; do
    addr=$(b33 || true)
    [ -n "$addr" ] && break
    i=$((i + 1))
    sleep 5
done
[ -n "$addr" ] || die "address not found yet; re-run this script later or open the web console: ssh -L 7070:127.0.0.1:7070 root@<vps>, http://127.0.0.1:7070/?page=i2p_tunnels -> multipath -> Encrypted B33 address"

printf '%s\n' "$addr" > "$ADDRESS"
chmod 600 "$ADDRESS"

echo
echo "address: $addr"
echo "(saved to server/OUTPROXY_ADDRESS.txt, back up server/i2pd/data/outproxy.dat)"
if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ANSIUTF8 "$addr"
fi
