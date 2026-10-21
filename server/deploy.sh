#!/bin/sh
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERVER="$ROOT/server"
DATA="$SERVER/i2pd/data"

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "run as root: sudo sh $0"

if ! command -v docker >/dev/null 2>&1; then
    log "installing Docker (get.docker.com)"
    command -v curl >/dev/null 2>&1 || { apt-get update -q && apt-get install -yq curl ca-certificates; }
    curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker >/dev/null 2>&1 || true
docker compose version >/dev/null 2>&1 || command -v docker-compose >/dev/null 2>&1 || {
    log "installing the docker compose plugin"
    apt-get update -q && apt-get install -yq docker-compose-plugin || apt-get install -yq docker-compose-v2
}
. "$ROOT/scripts/compose.sh"

mem_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
swap_kb=$(awk '/SwapTotal/ {print $2}' /proc/meminfo)
if [ $((mem_kb + swap_kb)) -lt 2500000 ] && [ ! -f /swapfile ]; then
    log "low memory ($((mem_kb / 1024)) MB): creating 2 GB /swapfile"
    fallocate -l 2G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=2048
    chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

modprobe tcp_bbr 2>/dev/null || true
{
    echo "net.ipv4.tcp_notsent_lowat = 131072"
    echo "net.core.default_qdisc = fq"
    grep -qw bbr /proc/sys/net/ipv4/tcp_available_congestion_control && echo "net.ipv4.tcp_congestion_control = bbr"
    echo "net.core.rmem_max = 4194304"
    echo "net.core.wmem_max = 4194304"
} > /etc/sysctl.d/90-outproxy.conf
sysctl -q -p /etc/sysctl.d/90-outproxy.conf || true

ENVF="$SERVER/i2pd/.env"
port=$(sed -n 's/^I2PD_PORT=\([0-9]*\)$/\1/p' "$ENVF" 2>/dev/null || true)
if [ -z "$port" ]; then
    port=$(awk 'BEGIN { srand(); print int(10000 + rand() * 20000) }')
    echo "I2PD_PORT=$port" > "$ENVF"
fi
log "i2pd transport port: $port (tcp+udp)"

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    log "ufw: allowing $port/tcp+udp"
    ufw allow "$port/tcp" >/dev/null
    ufw allow "$port/udp" >/dev/null
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    log "firewalld: allowing $port/tcp+udp"
    firewall-cmd -q --permanent --add-port="$port/tcp" --add-port="$port/udp" && firewall-cmd -q --reload
fi

chown -R 1000:1000 "$DATA"
chmod 700 "$DATA"

log "building images (first run takes 10-20 minutes)"
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

if command -v curl >/dev/null 2>&1; then
    ip=$(curl -s --max-time 20 --socks5-hostname 127.0.0.1:1080 https://api.ipify.org || true)
    [ -n "$ip" ] && log "exit IP through the local SOCKS: $ip" || warn "test request through 127.0.0.1:1080 failed"
fi

log "waiting for i2pd to publish the outproxy address (can take a few minutes)"
b33() {
    b32=$(curl -s --max-time 5 'http://127.0.0.1:7070/?page=i2p_tunnels' 2>/dev/null \
        | grep -oE 'local_destination&(amp;)?b32=[a-z2-7]{52}"?>multipath<' | grep -oE '[a-z2-7]{52}' | head -n1)
    [ -n "$b32" ] || return 0
    curl -s --max-time 5 "http://127.0.0.1:7070/?page=local_destination&b32=$b32" 2>/dev/null \
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

echo "$addr" > "$SERVER/OUTPROXY_ADDRESS.txt"
chmod 600 "$SERVER/OUTPROXY_ADDRESS.txt"

echo
echo "address: $addr"
echo "(saved to server/OUTPROXY_ADDRESS.txt, back up server/i2pd/data/outproxy.dat)"
if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ANSIUTF8 "$addr"
fi
