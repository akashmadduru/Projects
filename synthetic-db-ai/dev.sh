#!/usr/bin/env bash
REPO="$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))"
MSYS_NO_PATHCONV=1 docker run --rm --network sdai-net -v "$REPO:/repo" -w /repo/synthetic-db-ai \
  -e SDAI_PG_HOST=sdai-pg -e SDAI_PG_PASSWORD=sdai_scratch -e SDAI_REPO_ROOT=/repo \
  -e SDAI_STATE_DIR=/repo/synthetic-db-ai/state -e PYTHONDONTWRITEBYTECODE=1 --entrypoint "${SDAI_ENTRY:-python}" \
  synthetic-db-ai "${@:-main.py}"
