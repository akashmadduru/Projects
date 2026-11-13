#!/usr/bin/env bash
# =============================================================================
# destroy.sh - the single entry point that removes the whole platform from AWS.
#
#   ./deploy/aws/destroy.sh                        interactive: you must type the environment name
#   ./deploy/aws/destroy.sh --dry-run              show what would happen; change nothing
#   ./deploy/aws/destroy.sh --yes --confirm prod-name   non-interactive (CI)
#
# Options
#   --no-snapshot          do NOT take a final database snapshot (default: take one)
#   --delete-state-bucket  also delete the Terraform state bucket (default: keep it)
#   --delete-zone          also delete the Route 53 hosted zone (default: keep it, because
#                          deleting it changes your nameservers and breaks the domain)
#   --config FILE          use another config file
#
# WHAT IS DESTROYED: the EKS cluster and every node, all 13 services, the load balancer, RDS (after
# a final snapshot), Redis, Kafka, both S3 buckets and their contents, CloudFront, WAF, ECR images,
# ACM certificates, SES identity, secrets (immediately, no recovery window), log groups, alarms,
# the VPC. The KMS key is scheduled for deletion in 7 days.
#
# WHAT IS KEPT unless you ask: the database snapshot, the state bucket, the DNS zone.
#
# Order matters. The load balancer, the Karpenter-launched servers and the EBS volumes are created
# by controllers inside the cluster, NOT by Terraform, so Terraform cannot delete them and a
# VPC that still contains them cannot be deleted. They are removed first, in that order.
# =============================================================================
set -Eeuo pipefail
# shellcheck disable=SC2154  # rc is assigned inside the trap string itself
trap 'rc=$?; printf "\nFAILED at line %s (exit %s): %s\nFix the cause and re-run destroy.sh; every step tolerates things already being gone.\n" "$LINENO" "$rc" "$BASH_COMMAND" >&2' ERR

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

DRY_RUN=false; ASSUME_YES=false; CONFIRM=""; NO_SNAPSHOT=false
DELETE_STATE=false; DELETE_ZONE=false; CONFIG_FILE=""

usage() { sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --yes|-y) ASSUME_YES=true; shift ;;
    --confirm) CONFIRM="$2"; shift 2 ;;
    --no-snapshot) NO_SNAPSHOT=true; shift ;;
    --delete-state-bucket) DELETE_STATE=true; shift ;;
    --delete-zone) DELETE_ZONE=true; shift ;;
    --config) CONFIG_FILE="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) printf 'Unknown option: %s\n\n' "$1" >&2; usage 1 ;;
  esac
done
export CONFIG_FILE DRY_RUN ASSUME_YES

load_config
cd "$ROOT"
need aws
need terraform
resolve_account

dry() { [[ "$DRY_RUN" == "true" ]]; }

# ---------------------------------------------------------------------------------------------
confirm_destroy() {
  step "Destroy $NAME"
  cat <<EOF
  AWS account : $ACCOUNT_ID
  Region      : $AWS_REGION
  Environment : $NAME
  Domain      : $DOMAIN_NAME  (zone $([[ "$DELETE_ZONE" == "true" ]] && echo "DELETED" || echo "kept"))

  This deletes the cluster, all workloads and data stores, both web apps and their S3 content,
  CloudFront, the WAF, the container images and the secrets.
  Database    : $([[ "$NO_SNAPSHOT" == "true" ]] && echo "NO final snapshot (data is unrecoverable)" || echo "a final snapshot is taken first")
  State bucket: $([[ "$DELETE_STATE" == "true" ]] && echo "DELETED" || echo "kept")

EOF
  dry && { log "Dry run: nothing below is executed."; return; }
  if [[ -n "$CONFIRM" ]]; then
    [[ "$CONFIRM" == "$NAME" ]] || die "--confirm '$CONFIRM' does not match the environment name '$NAME'."
    [[ "$ASSUME_YES" == "true" ]] || die "--confirm needs --yes as well."
    return
  fi
  [[ "$ASSUME_YES" == "true" ]] && die "--yes also needs --confirm $NAME, so a typo in CI cannot delete the wrong environment."
  local typed
  read -r -p "Type the environment name ($NAME) to confirm: " typed
  [[ "$typed" == "$NAME" ]] || die "Not confirmed."
}

# ---------------------------------------------------------------------------------------------
alb_gone() { ! aws elbv2 describe-load-balancers --names "${NAME}-alb" >/dev/null 2>&1; }

lbc_sgs() {
  aws ec2 describe-security-groups --filters "Name=tag:elbv2.k8s.aws/cluster,Values=$CLUSTER" \
    --query 'SecurityGroups[].GroupId' --output text 2>/dev/null
}

karpenter_instances() {
  aws ec2 describe-instances \
    --filters "Name=tag:eks:eks-cluster-name,Values=$CLUSTER" "Name=tag-key,Values=karpenter.sh/nodepool" \
              "Name=instance-state-name,Values=pending,running,stopping,shutting-down" \
    --query 'Reservations[].Instances[].InstanceId' --output text 2>/dev/null
}

wait_until() {
  # wait_until <timeout-seconds> <description> <command...>
  local timeout=$1 what=$2 waited=0
  shift 2
  until "$@"; do
    if ((waited >= timeout)); then warn "Timed out waiting for: $what"; return 1; fi
    sleep 15; waited=$((waited + 15))
  done
}

step_kubernetes() {
  step "1/6 Kubernetes: ingress and load balancer, workloads, nodes, volumes"
  if ! aws eks describe-cluster --name "$CLUSTER" >/dev/null 2>&1; then
    log "Cluster $CLUSTER does not exist; skipping."
    return
  fi
  need kubectl; need helm
  if dry; then log "(dry-run) would delete the ingress, uninstall releases, delete Karpenter nodes and PVCs"; return; fi

  aws eks update-kubeconfig --name "$CLUSTER" --region "$AWS_REGION" --kubeconfig "$W_STATE/kubeconfig" >/dev/null
  if ! kubectl --request-timeout=20s get ns >/dev/null 2>&1; then
    warn "The Kubernetes API is not reachable from here (check KUBERNETES_API_ALLOWED_CIDRS). Controller-created resources will be swept by tag afterwards."
    return
  fi

  # 1. The ALB is created by the AWS Load Balancer Controller. Delete the Ingress while the
  #    controller is still alive and wait until the ALB and its security groups are really gone.
  kubectl -n "$NAMESPACE" delete ingress --all --wait=true --timeout=300s || true
  wait_until 600 "the load balancer to be deleted" alb_gone || true
  wait_until 300 "the controller's security groups to be deleted" bash -c "[[ -z \"\$(aws ec2 describe-security-groups --filters Name=tag:elbv2.k8s.aws/cluster,Values=$CLUSTER --query 'SecurityGroups[].GroupId' --output text)\" ]]" || true

  # 2. Workloads. Removing the release deletes the Deployments, Services, PVCs, and the Karpenter
  #    NodePools / EC2NodeClass (their finalizers make Karpenter terminate the nodes it launched).
  helm uninstall ecommerce -n "$NAMESPACE" --wait --timeout 15m 2>/dev/null || true
  kubectl delete nodeclaims --all --wait=true --timeout=600s 2>/dev/null || true
  kubectl delete ec2nodeclass --all --wait=true --timeout=300s 2>/dev/null || true

  # 3. Persistent volumes: the CSI driver deletes the EBS volume when the PV goes. It must still be
  #    running (it is an EKS add-on, removed with the cluster), so wait for the PVs now.
  kubectl delete pvc --all -n "$NAMESPACE" --wait=true --timeout=300s 2>/dev/null || true
  wait_until 300 "persistent volumes to be deleted" bash -c '[[ -z "$(kubectl get pv -o name 2>/dev/null)" ]]' || true

  # 4. Controllers, in the reverse order of installation. Karpenter last of those that matter.
  local r
  for r in karpenter:kube-system aws-load-balancer-controller:kube-system external-secrets:external-secrets metrics-server:kube-system; do
    helm uninstall "${r%%:*}" -n "${r##*:}" --wait --timeout 5m 2>/dev/null || true
  done
  kubectl delete namespace "$NAMESPACE" external-secrets --wait=false 2>/dev/null || true

  # 5. Any server Karpenter launched must be gone, or its network interface blocks VPC deletion.
  wait_until 600 "Karpenter nodes to terminate" bash -c "[[ -z \"\$(aws ec2 describe-instances --filters Name=tag:eks:eks-cluster-name,Values=$CLUSTER Name=tag-key,Values=karpenter.sh/nodepool Name=instance-state-name,Values=pending,running,stopping,shutting-down --query 'Reservations[].Instances[].InstanceId' --output text)\" ]]" || {
    local ids; ids="$(karpenter_instances)"
    if [[ -n "$ids" ]]; then
      warn "Terminating leftover nodes: $ids"
      # shellcheck disable=SC2086
      aws ec2 terminate-instances --instance-ids $ids >/dev/null || true
      sleep 60
    fi
  }
}

step_dns() {
  step "2/6 DNS record for api.$DOMAIN_NAME"
  local zone; zone="$(state_zone_id || true)"
  if [[ -z "$zone" ]]; then log "No hosted zone found; skipping."; return; fi
  dry && { log "(dry-run) would delete api.$DOMAIN_NAME from zone $zone"; return; }
  local batch="$STATE_DIR/dns-delete.json"
  aws route53 list-resource-record-sets --hosted-zone-id "$zone" \
    --query "{Changes: ResourceRecordSets[?Name=='api.${DOMAIN_NAME}.' && Type=='A'].{Action: 'DELETE', ResourceRecordSet: @}}" \
    --output json >"$batch"
  if grep -q '"Action"' "$batch"; then
    aws route53 change-resource-record-sets --hosted-zone-id "$zone" \
      --change-batch "file://deploy/aws/.state/$ENVIRONMENT/dns-delete.json" >/dev/null
    log "Removed api.$DOMAIN_NAME"
  else
    log "No api record present."
  fi
  rm -f "$batch"
}

step_database() {
  step "3/6 Database (final snapshot, then delete)"
  local id="${NAME}-postgres" snap
  if ! aws rds describe-db-instances --db-instance-identifier "$id" >/dev/null 2>&1; then
    log "RDS instance $id does not exist; skipping."
    return
  fi
  if dry; then
    log "(dry-run) would disable deletion protection and delete $id $([[ "$NO_SNAPSHOT" == "true" ]] && echo "WITHOUT" || echo "with") a final snapshot"
    return
  fi
  aws rds wait db-instance-available --db-instance-identifier "$id" || true
  aws rds modify-db-instance --db-instance-identifier "$id" --no-deletion-protection --apply-immediately >/dev/null
  aws rds wait db-instance-available --db-instance-identifier "$id" || true
  if [[ "$NO_SNAPSHOT" == "true" ]]; then
    warn "Deleting the database WITHOUT a final snapshot."
    aws rds delete-db-instance --db-instance-identifier "$id" --skip-final-snapshot --delete-automated-backups >/dev/null
  else
    snap="${id}-final-$(date +%Y%m%d-%H%M%S)"
    log "Final snapshot: $snap"
    aws rds delete-db-instance --db-instance-identifier "$id" --final-db-snapshot-identifier "$snap" --delete-automated-backups >/dev/null
  fi
  log "Waiting for RDS to finish deleting (about 5-10 minutes)..."
  aws rds wait db-instance-deleted --db-instance-identifier "$id"
}

step_terraform() {
  step "4/6 Terraform destroy"
  if ! aws s3api head-object --bucket "$STATE_BUCKET" --key "$ENVIRONMENT/terraform.tfstate" >/dev/null 2>&1; then
    log "No Terraform state for $ENVIRONMENT in s3://$STATE_BUCKET; nothing to destroy."
    return
  fi
  local zone; zone="$(state_zone_id || true)"
  export_terraform_vars "${zone:-unknown}"
  # Required variables have no defaults; their values are irrelevant for a destroy.
  : "${TF_VAR_stripe_secret_key:=destroy}"; : "${TF_VAR_stripe_publishable_key:=destroy}"; : "${TF_VAR_stripe_webhook_secret:=destroy}"
  export TF_VAR_stripe_secret_key TF_VAR_stripe_publishable_key TF_VAR_stripe_webhook_secret

  tf_init
  if dry; then
    tf plan -destroy -input=false
    return
  fi
  log "Destroying. CloudFront and the WAF/certificates can take 10-20 minutes."
  if ! tf destroy -input=false -auto-approve; then
    warn "terraform destroy failed. Sweeping controller-created leftovers and retrying once."
    step_sweep quiet
    tf destroy -input=false -auto-approve
  fi
}

step_sweep() {
  [[ "${1:-}" == "quiet" ]] || step "5/6 Sweep leftovers outside Terraform"
  dry && { log "(dry-run) would delete the keystore secret, log groups, orphaned volumes/security groups"; return; }

  aws secretsmanager delete-secret --secret-id "$NAME/jwt-keystore" --force-delete-without-recovery >/dev/null 2>&1 \
    && log "Deleted secret $NAME/jwt-keystore" || true

  local g p
  for p in "/aws/containerinsights/$CLUSTER" "/aws/eks/$CLUSTER" "/aws/msk/$NAME"; do
    for g in $(aws logs describe-log-groups --log-group-name-prefix "$p" --query 'logGroups[].logGroupName' --output text 2>/dev/null); do
      aws logs delete-log-group --log-group-name "$g" >/dev/null 2>&1 && log "Deleted log group $g" || true
    done
  done

  local alb_arn
  alb_arn="$(aws elbv2 describe-load-balancers --names "${NAME}-alb" --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null || true)"
  if [[ -n "$alb_arn" && "$alb_arn" != "None" ]]; then
    aws elbv2 delete-load-balancer --load-balancer-arn "$alb_arn" >/dev/null 2>&1 && log "Deleted leftover ALB" || true
    sleep 30
  fi

  local v
  for v in $(aws ec2 describe-volumes --filters "Name=tag:kubernetes.io/cluster/$CLUSTER,Values=owned" \
             --query 'Volumes[?State==`available`].VolumeId' --output text 2>/dev/null); do
    aws ec2 delete-volume --volume-id "$v" >/dev/null 2>&1 && log "Deleted orphaned volume $v" || true
  done

  local sg
  for sg in $(lbc_sgs); do
    aws ec2 delete-security-group --group-id "$sg" >/dev/null 2>&1 && log "Deleted leftover security group $sg" || true
  done

  [[ "${1:-}" == "quiet" ]] && return 0
  # Anything still tagged for this environment is reported, never silently ignored.
  local left
  left="$(aws resourcegroupstaggingapi get-resources --tag-filters "Key=Project,Values=$PROJECT" "Key=Environment,Values=$ENVIRONMENT" \
          --query 'ResourceTagMappingList[].ResourceARN' --output text 2>/dev/null | tr '\t' '\n' | grep -v '^$' || true)"
  if [[ -n "$left" ]]; then
    warn "Resources still tagged Project=$PROJECT Environment=$ENVIRONMENT (some deleted resources linger in this index for a while):"
    printf '    %s\n' $left
  else
    log "No tagged resources remain."
  fi
}

step_optional() {
  step "6/6 Optional: state bucket and DNS zone"
  if [[ "$DELETE_STATE" == "true" ]]; then
    if dry; then log "(dry-run) would delete s3://$STATE_BUCKET"; else
      log "Deleting state bucket s3://$STATE_BUCKET"
      empty_and_delete_bucket "$STATE_BUCKET"
    fi
  else
    log "Keeping state bucket s3://$STATE_BUCKET (use --delete-state-bucket to remove it)."
  fi

  if [[ "$DELETE_ZONE" == "true" ]]; then
    local zone; zone="$(state_zone_id || true)"
    if [[ -n "$zone" ]] && ! dry; then
      local batch="$STATE_DIR/zone-delete.json"
      aws route53 list-resource-record-sets --hosted-zone-id "$zone" \
        --query "{Changes: ResourceRecordSets[?Type!='NS' && Type!='SOA'].{Action: 'DELETE', ResourceRecordSet: @}}" \
        --output json >"$batch"
      if grep -q '"Action"' "$batch"; then
        aws route53 change-resource-record-sets --hosted-zone-id "$zone" \
          --change-batch "file://deploy/aws/.state/$ENVIRONMENT/zone-delete.json" >/dev/null
      fi
      aws route53 delete-hosted-zone --id "$zone" >/dev/null && log "Deleted hosted zone $zone"
    else
      log "(dry-run or no zone) hosted zone left alone"
    fi
  else
    log "Keeping the Route 53 zone for $DOMAIN_NAME (use --delete-zone to remove it)."
  fi
}

# ---------------------------------------------------------------------------------------------
confirm_destroy
step_kubernetes
step_dns
step_database
step_terraform
step_sweep
step_optional

if ! dry; then
  rm -rf "$STATE_DIR"
  printf '\n%sDestroyed: %s%s\n' "$C_GREEN" "$NAME" "$C_OFF"
  [[ "$NO_SNAPSHOT" == "true" ]] || printf 'The final database snapshot (%s-postgres-final-*) is kept; delete it in the RDS console when you no longer need it.\n' "$NAME"
fi
