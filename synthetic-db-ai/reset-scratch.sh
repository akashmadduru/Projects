#!/usr/bin/env bash
# dev: recreate the throw-away Postgres used for local testing (container sdai-pg on port 55432) and rebuild the schemas
docker rm -f sdai-pg >/dev/null 2>&1
docker network create sdai-net >/dev/null 2>&1
docker run -d --name sdai-pg --network sdai-net -e POSTGRES_PASSWORD=sdai_scratch -p 127.0.0.1:55432:5432 --shm-size=512m postgres:17 \
  -c fsync=off -c synchronous_commit=off -c max_connections=200 -c shared_buffers=512MB >/dev/null
until docker exec sdai-pg pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done; sleep 2
cd "$(dirname "$0")" && rm -rf state && ./dev.sh main.py scratch
