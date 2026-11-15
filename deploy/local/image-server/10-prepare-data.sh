#!/bin/sh
# Runs as root from /docker-entrypoint.d before nginx starts: the named volume is created root-owned,
# but the worker processes (user nginx) must be able to write into it.
set -e
mkdir -p /data/images
chown -R nginx:nginx /data/images
