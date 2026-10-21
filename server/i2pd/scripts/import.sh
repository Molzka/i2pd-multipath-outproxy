#!/bin/sh
set -e
[ -n "$1" ] || { echo "usage: $0 image.tar" >&2; exit 1; }
docker image load -i "$1"
