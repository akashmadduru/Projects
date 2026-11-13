#!/usr/bin/env bash
# Shared helpers for deploy.sh and destroy.sh. Sourced, never executed.
# shellcheck disable=SC2034  # variables defined here are consumed by the scripts that source this

# ---------------------------------------------------------------------------------------------
# Paths. Native Windows tools (terraform.exe, helm.exe, aws.exe...) cannot read "/c/Users/..."
# paths, so every path handed to a tool is the "C:/Users/..." form (cygpath -m) and path
# conversion by Git Bash is switched off. On Linux/macOS winpath is the identity.
# ---------------------------------------------------------------------------------------------
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

AWS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$AWS_DIR/../.." && pwd)"
W_ROOT="$(winpath "$ROOT")"
W_AWS="$(winpath "$AWS_DIR")"

# ---------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------
if [[ -t 1 ]]; then
  C_BLUE=$'\033[1;34m'; C_GREEN=$'\033[1;32m'; C_YELLOW=$'\033[1;33m'; C_RED=$'\033[1;31m'; C_OFF=$'\033[0m'
else
  C_BLUE=''; C_GREEN=''; C_YELLOW=''; C_RED=''; C_OFF=''
fi

log()  { printf '%s[%s]%s %s\n' "$C_BLUE" "$(date +%H:%M:%S)" "$C_OFF" "$*"; }
step() { printf '\n%s==> %s%s\n' "$C_GREEN" "$*" "$C_OFF"; }
warn() { printf '%sWARNING:%s %s\n' "$C_YELLOW" "$C_OFF" "$*" >&2; }
die()  { printf '%sERROR:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' is not installed or not on PATH. ${2:-}"
}

# Run a command, or only print it when DRY_RUN=true.
run() {
  if [[ "${DRY_RUN:-false}" == "true" ]]; then
    printf '  (dry-run) %s\n' "$*"
  else
    "$@"
  fi
}

confirm() {
  # confirm "question" -> returns 0 when the user agrees. --yes answers for you.
  [[ "${ASSUME_YES:-false}" == "true" ]] && return 0
  local reply
  read -r -p "$1 [y/N] " reply || return 1
  [[ "$reply" =~ ^[Yy]$ ]]
}

retry() {
  # retry <attempts> <sleep-seconds> <command...>
  local attempts=$1 delay=$2 n=1
  shift 2
  until "$@"; do
    if ((n >= attempts)); then return 1; fi
    n=$((n + 1))
    sleep "$delay"
  done
}

# ---------------------------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------------------------
load_config() {
  local file="${CONFIG_FILE:-$AWS_DIR/config.env}"
  [[ -f "$file" ]] || die "No config file at $file. Copy deploy/aws/config.example.env to deploy/aws/config.env and fill it in."
  # tr strips Windows line endings that editors add; they would otherwise end up inside values.
  set -a
  # shellcheck disable=SC1090
  source <(tr -d '\r' <"$file")
  set +a

  AWS_REGION="${AWS_REGION:-ap-south-1}"
  PROJECT="${PROJECT:-ecommerce}"
  ENVIRONMENT="${ENVIRONMENT:-prod}"
  SIZE_PROFILE="${SIZE_PROFILE:-lean}"
  KUBERNETES_API_ALLOWED_CIDRS="${KUBERNETES_API_ALLOWED_CIDRS:-0.0.0.0/0}"
  RESTRICT_ALB_TO_CLOUDFRONT="${RESTRICT_ALB_TO_CLOUDFRONT:-true}"
  SKIP_FRONTENDS="${SKIP_FRONTENDS:-false}"

  NAME="${PROJECT}-${ENVIRONMENT}"
  CLUSTER="$NAME"
  NAMESPACE="ecommerce"
  STATE_DIR="$AWS_DIR/.state/$ENVIRONMENT"
  mkdir -p "$STATE_DIR"
  W_STATE="$(winpath "$STATE_DIR")"
  TF_DIR_REL="deploy/aws/terraform/envs/prod"   # relative to the repo root (commands run from there)

  export AWS_REGION
  export AWS_DEFAULT_REGION="$AWS_REGION"
  [[ -n "${AWS_PROFILE:-}" ]] && export AWS_PROFILE || unset AWS_PROFILE
  export AWS_PAGER=""
  export KUBECONFIG="$W_STATE/kubeconfig"
}

# Variables Terraform reads from the environment (so no secret ever appears on a command line).
export_terraform_vars() {
  local zone_id="${1:-}"
  export TF_VAR_region="$AWS_REGION"
  export TF_VAR_project="$PROJECT"
  export TF_VAR_environment="$ENVIRONMENT"
  export TF_VAR_domain_name="$DOMAIN_NAME"
  export TF_VAR_hosted_zone_id="$zone_id"
  export TF_VAR_size_profile="$SIZE_PROFILE"
  export TF_VAR_alert_email="${ALERT_EMAIL:-}"
  export TF_VAR_google_client_id="${GOOGLE_CLIENT_ID:-}"
  export TF_VAR_google_client_secret="${GOOGLE_CLIENT_SECRET:-}"
  export TF_VAR_stripe_secret_key="${STRIPE_SECRET_KEY:-}"
  export TF_VAR_stripe_publishable_key="${STRIPE_PUBLISHABLE_KEY:-}"
  export TF_VAR_stripe_webhook_secret="${STRIPE_WEBHOOK_SECRET:-}"
  # comma list -> JSON list
  # shellcheck disable=SC2089,SC2090  # a JSON list literal is exactly what Terraform expects
  TF_VAR_api_access_cidrs="[\"${KUBERNETES_API_ALLOWED_CIDRS//,/\",\"}\"]"
  # shellcheck disable=SC2090
  export TF_VAR_api_access_cidrs
}

# ---------------------------------------------------------------------------------------------
# Terraform
# ---------------------------------------------------------------------------------------------
tf() { (cd "$ROOT" && terraform -chdir="$TF_DIR_REL" "$@"); }
tfo() { tf output -raw "$1" 2>/dev/null; }

tf_init() {
  tf init -input=false -reconfigure \
    -backend-config="bucket=$STATE_BUCKET" \
    -backend-config="key=$ENVIRONMENT/terraform.tfstate" \
    -backend-config="region=$AWS_REGION" \
    -backend-config="encrypt=true" \
    -backend-config="use_lockfile=true"
}

# ---------------------------------------------------------------------------------------------
# AWS identity and the Terraform state bucket (shared by deploy.sh and destroy.sh)
# ---------------------------------------------------------------------------------------------
resolve_account() {
  ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text 2>/dev/null)" \
    || die "AWS credentials are not working. Run 'aws sso login' / 'aws configure' (profile: ${AWS_PROFILE:-default})."
  STATE_BUCKET="${PROJECT}-${ENVIRONMENT}-tfstate-${ACCOUNT_ID}"
  REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
}

state_zone_id() {
  # The hosted zone id deploy.sh recorded, or looked up by domain name.
  if [[ -s "$STATE_DIR/zone_id" ]]; then cat "$STATE_DIR/zone_id"; return; fi
  local id
  id="$(aws route53 list-hosted-zones-by-name --dns-name "${DOMAIN_NAME}." --max-items 1 \
        --query "HostedZones[?Name=='${DOMAIN_NAME}.'].Id | [0]" --output text 2>/dev/null || true)"
  [[ -n "$id" && "$id" != "None" ]] && printf '%s' "${id##*/}"
}

kubectl_ready() {
  [[ -f "$STATE_DIR/kubeconfig" ]] && kubectl --request-timeout=15s get ns >/dev/null 2>&1
}

# Delete every version and delete-marker in a (versioned) bucket, then the bucket.
empty_and_delete_bucket() {
  local bucket="$1" batch
  aws s3api head-bucket --bucket "$bucket" >/dev/null 2>&1 || return 0
  while true; do
    batch="$(aws s3api list-object-versions --bucket "$bucket" --max-items 500 \
      --query '{Objects: [Versions, DeleteMarkers][].{Key: Key, VersionId: VersionId}}' --output json 2>/dev/null || true)"
    [[ -z "$batch" || "$batch" == *'"Objects": null'* || "$batch" == *'"Objects": []'* ]] && break
    printf '%s' "$batch" >"$STATE_DIR/.delete-batch.json"
    aws s3api delete-objects --bucket "$bucket" \
      --delete "file://deploy/aws/.state/$ENVIRONMENT/.delete-batch.json" >/dev/null || break
  done
  rm -f "$STATE_DIR/.delete-batch.json"
  aws s3api delete-bucket --bucket "$bucket"
}
