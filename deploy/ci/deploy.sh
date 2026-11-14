#!/usr/bin/env bash
# =============================================================================
# Per-environment deploy hook called by .github/workflows/cd-images.yml and
# promote.yml once the repository variable DEPLOY_ENABLED is 'true'.
#
#   deploy.sh <dev|staging|production> <image-tag> <image-repo-prefix>
#
# INTENTIONALLY NOT IMPLEMENTED. Until a target exists for each environment
# this fails loudly instead of pretending to deploy. Fill in one `case` branch
# per environment (e.g. helm upgrade against that environment's cluster),
# using credentials stored on the matching GitHub Environment.
# =============================================================================
set -euo pipefail

env_name="${1:?environment required (dev|staging|production)}"
tag="${2:?image tag required}"
prefix="${3:?image repository prefix required}"

echo "Requested deploy: env=$env_name tag=$tag images=$prefix/<service>:$tag"

case "$env_name" in
  dev | staging | production)
    echo "::error::deploy/ci/deploy.sh has no deploy implementation for '$env_name' yet." >&2
    echo "Implement it here, or unset the DEPLOY_ENABLED variable to publish images only." >&2
    exit 1
    ;;
  *)
    echo "Unknown environment: $env_name" >&2
    exit 2
    ;;
esac
