#!/usr/bin/env bash
# =============================================================================
# Tears down this project's local Docker footprint: the infra/docker-compose.yml
# stack (postgres, zookeeper, eureka-server, gateway, auth-service,
# product-service) plus, optionally, the named data volumes and the images
# built from this repo's Dockerfiles.
#
# SCOPED TO THIS PROJECT ONLY. It never touches containers, images, or volumes
# belonging to other projects on the same machine - it operates strictly on the
# `ecommerce-platform` compose project (see `name:` in docker-compose.yml) plus
# any stray exited containers left over from an interrupted `docker compose
# build` of one of THIS repo's Dockerfiles.
#
# Usage:
#   ./infra/scripts/docker-cleanup.sh              # stop + remove containers, keep data + images
#   ./infra/scripts/docker-cleanup.sh --volumes     # also wipe postgres/zookeeper data
#   ./infra/scripts/docker-cleanup.sh --images      # also remove this repo's built images
#   ./infra/scripts/docker-cleanup.sh --all         # both of the above
# =============================================================================

set -euo pipefail

cd "$(dirname "$0")/../.."   # repo root

COMPOSE_FILE="infra/docker-compose.yml"
PROJECT_NAME="ecommerce-platform"
WIPE_VOLUMES=false
REMOVE_IMAGES=false

for arg in "$@"; do
  case "${arg}" in
    --volumes) WIPE_VOLUMES=true ;;
    --images)  REMOVE_IMAGES=true ;;
    --all)     WIPE_VOLUMES=true; REMOVE_IMAGES=true ;;
    -h|--help)
      sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "Unknown option: ${arg}" >&2; exit 1 ;;
  esac
done

echo "== Stopping and removing the ${PROJECT_NAME} compose stack =="
if [[ "${WIPE_VOLUMES}" == true ]]; then
  echo "   (including named volumes: postgres-data, zookeeper-data, zookeeper-datalog)"
  docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans
else
  docker compose -f "${COMPOSE_FILE}" down --remove-orphans
fi

echo
echo "== Removing stray containers left over from interrupted builds of this repo =="
# `docker compose build` runs each Dockerfile RUN step in its own throwaway
# container; if a build is killed mid-step, that container is orphaned instead
# of being cleaned up. It won't carry the compose project label, so `down`
# above never touches it - find it by the image it was building from instead.
stray_containers=$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT_NAME}" --filter "status=exited")
if [[ -n "${stray_containers}" ]]; then
  echo "${stray_containers}" | xargs docker rm -f
else
  echo "   none found"
fi

if [[ "${REMOVE_IMAGES}" == true ]]; then
  echo
  echo "== Removing images built from this repo's Dockerfiles =="
  built_images=$(docker images --format '{{.Repository}}:{{.Tag}}' \
    | grep -E "^${PROJECT_NAME}-(eureka-server|gateway|auth-service|product-service):" || true)
  if [[ -n "${built_images}" ]]; then
    echo "${built_images}"
    echo "${built_images}" | xargs docker rmi -f
  else
    echo "   none found"
  fi
fi

echo
echo "== Dangling images and build cache from this repo's builds =="
docker image prune -f >/dev/null
docker builder prune -f --filter "label=com.docker.compose.project=${PROJECT_NAME}" >/dev/null 2>&1 || true

echo
echo "Done."
if [[ "${WIPE_VOLUMES}" != true ]]; then
  echo "Data volumes were kept. Re-run with --volumes to wipe postgres/zookeeper data too."
fi
if [[ "${REMOVE_IMAGES}" != true ]]; then
  echo "Built images were kept (faster next 'up'). Re-run with --images to remove them too."
fi
