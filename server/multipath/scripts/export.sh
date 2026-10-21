#!/bin/sh
set -e
cd "$(dirname "$0")/.."
docker image save -o multipath_image.tar mymultipath:latest
echo "saved multipath_image.tar"
