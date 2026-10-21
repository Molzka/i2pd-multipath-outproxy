#!/bin/sh
set -e
cd "$(dirname "$0")/.."
. ../../scripts/compose.sh
compose build --pull
