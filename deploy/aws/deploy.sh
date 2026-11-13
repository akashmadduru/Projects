#!/usr/bin/env bash
# =============================================================================
# deploy.sh - the single entry point that puts the whole platform on AWS.
#
#   ./deploy/aws/deploy.sh                      deploy / update everything
#   ./deploy/aws/deploy.sh --plan               terraform plan only
#   ./deploy/aws/deploy.sh --only images,platform
#   ./deploy/aws/deploy.sh --from platform      resume from a stage
#   ./deploy/aws/deploy.sh --yes                no prompts (CI)
#
# Stages, in order:
#   preflight  tools, credentials, config
#   backend    Terraform state bucket + Route 53 zone
#   infra      terraform apply (VPC, EKS, RDS, Redis, Kafka, ECR, CloudFront, WAF, SES, secrets)
#   keystore   JWT signing key into Secrets Manager (created once, never replaced)
#   access     kubeconfig + wait for nodes
#   addons     metrics-server, AWS Load Balancer Controller, External Secrets, Karpenter
#   images     compile once, build + push 13 images to ECR
#   database   create the 12 databases and roles on RDS
#   platform   helm install of all services, autoscalers, network policies, ingress
#   dns        api.<domain> -> load balancer
#   frontends  build both SPAs, upload to S3, invalidate CloudFront
#   retention  log retention
#   smoke      end-to-end checks through CloudFront
#
# Every stage is idempotent, so a failed run is fixed and simply re-run.
# Remove everything again with ./deploy/aws/destroy.sh.
# =============================================================================
set -Eeuo pipefail
# shellcheck disable=SC2154  # rc is assigned inside the trap string itself
trap 'rc=$?; printf "\nFAILED at line %s (exit %s): %s\n" "$LINENO" "$rc" "$BASH_COMMAND" >&2' ERR

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
# shellcheck source=lib/stages.sh
source "$AWS_DIR/lib/stages.sh"

ALL_STAGES=(preflight backend infra keystore access addons images database platform dns frontends retention smoke)
ONLY=""; FROM=""; PLAN_ONLY=false; ASSUME_YES=false; CONFIG_FILE=""

usage() { sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config) CONFIG_FILE="$2"; shift 2 ;;
    --plan) PLAN_ONLY=true; shift ;;
    --only) ONLY="$2"; shift 2 ;;
    --from) FROM="$2"; shift 2 ;;
    --yes|-y) ASSUME_YES=true; shift ;;
    -h|--help) usage 0 ;;
    *) printf 'Unknown option: %s\n\n' "$1" >&2; usage 1 ;;
  esac
done
export PLAN_ONLY ASSUME_YES CONFIG_FILE

load_config
cd "$ROOT"

in_list() { local x; for x in $2; do [[ "$x" == "$1" ]] && return 0; done; return 1; }

if [[ -n "$FROM" ]] && ! in_list "$FROM" "${ALL_STAGES[*]}"; then
  die "Unknown stage '$FROM'. Valid: ${ALL_STAGES[*]}"
fi
for s in ${ONLY//,/ }; do
  in_list "$s" "${ALL_STAGES[*]}" || die "Unknown stage '$s'. Valid: ${ALL_STAGES[*]}"
done

selected=()
started=false
[[ -z "$FROM" ]] && started=true
for s in "${ALL_STAGES[@]}"; do
  if [[ -n "$ONLY" ]]; then
    if in_list "$s" "${ONLY//,/ }"; then selected+=("$s"); fi
  else
    if [[ "$s" == "$FROM" ]]; then started=true; fi
    if $started; then selected+=("$s"); fi
  fi
done
[[ ${#selected[@]} -gt 0 ]] || die "No stages selected. Valid: ${ALL_STAGES[*]}"

# Partial runs still need the account, state bucket and zone id resolved.
if ! in_list preflight "${selected[*]}"; then
  resolve_account
  [[ -s "$STATE_DIR/zone_id" ]] || stage_backend
fi

log "Stages: ${selected[*]}"
for s in "${selected[@]}"; do
  "stage_$s"
done

[[ "$PLAN_ONLY" == "true" ]] || print_summary
