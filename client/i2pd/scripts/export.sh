#!/bin/sh
set -e
cd "$(dirname "$0")/.."
docker image save -o i2pd_image.tar myi2pd:latest
echo "saved i2pd_image.tar"
