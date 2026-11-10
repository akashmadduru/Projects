#!/usr/bin/env bash
# =============================================================================
# Brings up this project's complete Docker stack for local development.
#
# Starts all services from infra/docker-compose.yml in the correct dependency
# order and waits for health checks to pass.
#
# USAGE
#   ./infra/scripts/docker-runup.sh             # start and wait for health
#   ./infra/scripts/docker-runup.sh --rebuild   # rebuild images, then start
#   ./infra/scripts/docker-runup.sh --no-wait   # start without waiting
# =============================================================================

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

rebuild=false
no_wait=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --rebuild)
            rebuild=true
            shift
            ;;
        --no-wait)
            no_wait=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Repo root: two levels up from this script
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"

compose_file='infra/docker-compose.yml'
project_name='ecommerce-platform'

echo "== Starting $project_name Docker stack =="
echo ""

up_args=('compose' '-f' "$compose_file" 'up' '-d')
if [[ "$rebuild" == true ]]; then
    echo "Rebuilding images..."
    up_args+=('--build')
fi

docker "${up_args[@]}"

if [[ "$no_wait" != true ]]; then
    echo ""
    echo "== Waiting for services to become healthy =="
    echo ""

    services=(
        'postgres'
        'zookeeper'
        'kafka'
        'redis'
        'eureka-server'
        'auth-service'
        'gateway'
        'product-service'
        'inventory-service'
    )

    max_wait_seconds=120
    all_healthy=true

    for service in "${services[@]}"; do
        echo "Waiting for $service to be healthy..."
        start_time=$(date +%s)
        container_name="ecommerce-${service//-/}"

        while true; do
            health=$(docker inspect "$container_name" --format='{{.State.Health.Status}}' 2>/dev/null || echo "none")

            if [[ "$health" == "healthy" ]]; then
                echo -e "${GREEN}✓${NC} $service is healthy"
                break
            fi

            current_time=$(date +%s)
            elapsed=$((current_time - start_time))

            if [[ $elapsed -ge $max_wait_seconds ]]; then
                echo -e "${RED}✗${NC} $service did not become healthy within $max_wait_seconds seconds"
                all_healthy=false
                break
            fi

            sleep 2
        done
    done

    echo ""
    if [[ "$all_healthy" == true ]]; then
        echo -e "${GREEN}✓ All services are healthy and ready!${NC}"
        echo ""
        echo "Service URLs:"
        echo "  Gateway:              http://localhost:8080"
        echo "  Auth Service:         http://127.0.0.1:8081"
        echo "  Product Service:      http://127.0.0.1:8082"
        echo "  Inventory Service:    http://127.0.0.1:8083"
        echo "  Eureka Registry:      http://127.0.0.1:8761"
        echo "  Postgres:             localhost:5432 (postgres / postgres)"
        echo "  Redis:                localhost:6379"
        echo "  Zookeeper:            localhost:2181"
    else
        echo -e "${RED}✗ Some services failed to become healthy. Check logs with:${NC}"
        echo "  docker compose -f $compose_file logs"
        exit 1
    fi
else
    echo "Services started. Skipping health checks."
    echo ""
    echo "To check service status, run: docker compose -f $compose_file ps"
    echo "To view logs, run: docker compose -f $compose_file logs"
fi
