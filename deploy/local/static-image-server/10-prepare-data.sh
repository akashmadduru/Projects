#!/bin/sh
# Runs as root from /docker-entrypoint.d before nginx starts.
# 1. The named volume is created root-owned; nginx workers (user nginx) must be able to write to it.
# 2. Copy the existing generated product images (read-only bind mount at /seed) into the volume.
#    -n never overwrites, so an image replaced through the API is not reverted on restart, and
#    images generated since the last start are picked up.
set -e
mkdir -p /data/product-images
if [ -d /seed ]; then
  cp -n /seed/*.jpg /data/product-images/ 2>/dev/null || true
  echo "static-image-server: $(ls /data/product-images | wc -l) files in /data/product-images"
fi
chown -R nginx:nginx /data/product-images
