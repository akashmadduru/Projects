#!/usr/bin/env bash
# The deploy stages, one function each. Sourced by deploy.sh after common.sh.
# Every stage is idempotent: re-running it changes nothing that is already correct.
# shellcheck disable=SC2034

SERVICES=(gateway auth-service product-service inventory-service rating-service user-service
          cart-service order-service payment-service promotion-service notification-service
          seller-service support-service)

# Chart versions are pinned. They were chosen from the compatibility tables for the Kubernetes
# version in terraform/envs/prod/variables.tf; if a pinned version is ever withdrawn, bump it here.
METRICS_SERVER_CHART_VERSION="3.12.2"
LBC_CHART_VERSION="1.10.1"
ESO_CHART_VERSION="0.10.7"
KARPENTER_CHART_VERSION="1.1.1"

lean() { [[ "$SIZE_PROFILE" == "lean" ]]; }

# sha1 of stdin, whichever tool this platform has.
sha1() { if command -v sha1sum >/dev/null 2>&1; then sha1sum; else shasum -a 1; fi; }

# ---------------------------------------------------------------------------------------------
stage_preflight() {
  step "Preflight"

  need aws "Install AWS CLI v2: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html (Windows: winget install Amazon.AWSCLI)"
  need terraform "Install Terraform >= 1.10 (Windows: winget install Hashicorp.Terraform)"
  need kubectl "Install kubectl (Windows: winget install Kubernetes.kubectl)"
  need helm "Install Helm >= 3.12 (Windows: winget install Helm.Helm)"
  need docker "Install Docker Desktop and start it"
  need git
  need openssl "Git for Windows ships openssl; on Linux/macOS install it with your package manager"
  need curl
  if [[ "$SKIP_FRONTENDS" != "true" ]]; then
    need node "Install Node.js >= 20 (or set SKIP_FRONTENDS=true)"
    need npm
  fi

  local tfv
  tfv="$(terraform version | head -1 | sed -E 's/[^0-9]*([0-9]+\.[0-9]+).*/\1/')"
  awk -v v="$tfv" 'BEGIN{split(v,a,"."); exit !(a[1]>1 || (a[1]==1 && a[2]>=10))}' \
    || die "Terraform $tfv found; >= 1.10 is required (native S3 state locking)."

  docker info >/dev/null 2>&1 || die "Docker is installed but its daemon is not running. Start Docker Desktop."
  local mem_gb
  mem_gb="$(docker info --format '{{.MemTotal}}' 2>/dev/null | awk '{printf "%d", $1/1073741824}')"
  if [[ -n "$mem_gb" && "$mem_gb" -lt 6 ]]; then
    warn "Docker has only ${mem_gb} GB of memory; compiling the Java services needs about 6 GB. Raise it in Docker Desktop settings if the image build is killed."
  fi

  # --- configuration ------------------------------------------------------------------------
  [[ "$SIZE_PROFILE" == "lean" || "$SIZE_PROFILE" == "prod" ]] || die "SIZE_PROFILE must be 'lean' or 'prod'."
  [[ -n "${DOMAIN_NAME:-}" && "$DOMAIN_NAME" != "example.com" ]] || die "Set DOMAIN_NAME in config.env to a domain you control."
  [[ -n "${STRIPE_SECRET_KEY:-}" && -n "${STRIPE_PUBLISHABLE_KEY:-}" && -n "${STRIPE_WEBHOOK_SECRET:-}" ]] \
    || die "STRIPE_SECRET_KEY, STRIPE_PUBLISHABLE_KEY and STRIPE_WEBHOOK_SECRET are required: payment-service refuses to start in production without them."
  case "$STRIPE_SECRET_KEY" in
    sk_live_*|rk_live_*) : ;;
    sk_test_*|rk_test_*) warn "STRIPE_SECRET_KEY is a TEST key: no real money will move." ;;
    *) warn "STRIPE_SECRET_KEY does not look like a Stripe key (expected sk_live_... or sk_test_...)." ;;
  esac

  if [[ "$KUBERNETES_API_ALLOWED_CIDRS" == "0.0.0.0/0" ]]; then
    warn "KUBERNETES_API_ALLOWED_CIDRS is 0.0.0.0/0: the Kubernetes API is reachable from the whole internet (it still needs AWS credentials). Restrict it to your office / CI addresses for production."
  fi

  resolve_account
  log "AWS account : $ACCOUNT_ID"
  log "Region      : $AWS_REGION"
  log "Environment : $NAME  (size profile: $SIZE_PROFILE)"
  log "Domain      : $DOMAIN_NAME"

  if lean; then
    log "Lean profile: expect a few hundred USD/month while it runs."
  else
    warn "Prod profile: expect well over a thousand USD/month while it runs (Multi-AZ database, 3-AZ NAT, Kafka brokers)."
  fi
  confirm "Create or update this environment in AWS account $ACCOUNT_ID?" || die "Aborted."
}

# ---------------------------------------------------------------------------------------------
stage_backend() {
  step "Terraform state bucket and DNS zone"
  resolve_account

  if aws s3api head-bucket --bucket "$STATE_BUCKET" >/dev/null 2>&1; then
    log "State bucket $STATE_BUCKET exists."
  else
    log "Creating state bucket $STATE_BUCKET"
    if [[ "$AWS_REGION" == "us-east-1" ]]; then
      aws s3api create-bucket --bucket "$STATE_BUCKET" >/dev/null
    else
      aws s3api create-bucket --bucket "$STATE_BUCKET" \
        --create-bucket-configuration "LocationConstraint=$AWS_REGION" >/dev/null
    fi
    aws s3api put-bucket-versioning --bucket "$STATE_BUCKET" --versioning-configuration Status=Enabled
    aws s3api put-bucket-encryption --bucket "$STATE_BUCKET" \
      --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
    aws s3api put-public-access-block --bucket "$STATE_BUCKET" \
      --public-access-block-configuration 'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'
  fi

  local zone
  zone="$(state_zone_id || true)"
  if [[ -n "$zone" ]]; then
    log "Hosted zone for $DOMAIN_NAME: $zone"
  else
    log "No hosted zone for $DOMAIN_NAME; creating one."
    zone="$(aws route53 create-hosted-zone --name "$DOMAIN_NAME" --caller-reference "$NAME-$(date +%s)" \
            --query 'HostedZone.Id' --output text)"
    zone="${zone##*/}"
    printf '\nPoint the nameservers of %s at these Route 53 servers (at your registrar):\n\n' "$DOMAIN_NAME"
    aws route53 get-hosted-zone --id "$zone" --query 'DelegationSet.NameServers' --output text | tr '\t' '\n' | sed 's/^/    /'
    printf '\nCertificates are validated through DNS, so the next stage cannot finish until this is done.\n'
    if [[ "${ASSUME_YES:-false}" != "true" ]]; then
      read -r -p "Press Enter once the nameservers are updated (propagation can take a few minutes)... " _
    else
      warn "--yes given: continuing without waiting. If the certificate validation times out, set the nameservers and re-run."
    fi
  fi
  printf '%s' "$zone" >"$STATE_DIR/zone_id"
  export_terraform_vars "$zone"
}

# ---------------------------------------------------------------------------------------------
stage_infra() {
  step "Terraform: network, EKS, RDS, Redis, Kafka, ECR, edge, secrets"
  resolve_account
  export_terraform_vars "$(cat "$STATE_DIR/zone_id")"

  tf_init
  tf validate
  if [[ "${PLAN_ONLY:-false}" == "true" ]]; then
    tf plan -input=false
    log "Plan only: stopping here. Nothing but the state bucket and DNS zone was created."
    exit 0
  fi
  log "Applying. EKS, RDS, MSK and CloudFront each take 10-25 minutes; the first run is about 40 minutes."
  tf apply -input=false -auto-approve
}

# ---------------------------------------------------------------------------------------------
stage_keystore() {
  step "JWT signing keystore"
  local secret_id="$NAME/jwt-keystore"
  if aws secretsmanager describe-secret --secret-id "$secret_id" >/dev/null 2>&1; then
    log "Secret $secret_id exists; keeping the current signing key (replacing it would log everybody out)."
    return
  fi
  export_terraform_vars "$(cat "$STATE_DIR/zone_id")"
  local pass tmp
  pass="$(tfo jwt_keystore_password)" || die "Could not read jwt_keystore_password from Terraform outputs."
  tmp="$(mktemp -d)"

  # 2048-bit RSA, 10-year self-signed certificate, PKCS#12, alias "jwt-signing". The certificate is
  # only a container for the key: tokens are verified with the public key published at JWKS.
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$tmp/key.pem" -out "$tmp/cert.pem" -subj "/CN=ecommerce-auth/O=Platform" 2>/dev/null
  # The password goes through the environment, not the command line, so it never shows in `ps`.
  KEYSTORE_PASS="$pass" openssl pkcs12 -export -inkey "$tmp/key.pem" -in "$tmp/cert.pem" \
    -name jwt-signing -out "$tmp/jwt-keystore.p12" -passout env:KEYSTORE_PASS
  # Stored as one line of base64; External Secrets decodes it back to the binary file.
  aws secretsmanager create-secret --name "$secret_id" --kms-key-id "alias/$NAME" \
    --description "JWT signing keystore (PKCS#12, base64)" \
    --secret-string "$(base64 <"$tmp/jwt-keystore.p12" | tr -d '\n')" >/dev/null
  rm -rf "$tmp"
  log "Created $secret_id"
}

# ---------------------------------------------------------------------------------------------
stage_access() {
  step "Cluster access"
  aws eks update-kubeconfig --name "$CLUSTER" --region "$AWS_REGION" --kubeconfig "$W_STATE/kubeconfig" >/dev/null
  log "kubeconfig written to deploy/aws/.state/$ENVIRONMENT/kubeconfig (your own ~/.kube/config is untouched)"
  retry 20 15 kubectl --request-timeout=20s get nodes >/dev/null 2>&1 \
    || die "Cannot reach the Kubernetes API. If you set KUBERNETES_API_ALLOWED_CIDRS, make sure it includes this machine's public IP."
  kubectl wait --for=condition=Ready nodes --all --timeout=600s
}

# ---------------------------------------------------------------------------------------------
helm_install() {
  # helm_install <release> <chart> <namespace> <extra helm args...>
  local release="$1" chart="$2" ns="$3"
  shift 3
  helm upgrade --install "$release" "$chart" --namespace "$ns" --create-namespace --wait --timeout 10m "$@"
}

stage_addons() {
  step "Cluster add-ons: metrics-server, AWS Load Balancer Controller, External Secrets, Karpenter"
  export_terraform_vars "$(cat "$STATE_DIR/zone_id")"
  local vpc_id queue replicas=2
  vpc_id="$(tfo vpc_id)"; queue="$(tfo karpenter_queue_name)"
  lean && replicas=1

  helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ >/dev/null 2>&1 || true
  helm repo add eks https://aws.github.io/eks-charts >/dev/null 2>&1 || true
  helm repo add external-secrets https://charts.external-secrets.io >/dev/null 2>&1 || true
  helm repo update >/dev/null

  # The application namespace. Pods are written to satisfy the "restricted" Pod Security level;
  # "baseline" is enforced so the in-cluster Jaeger (which runs as its own image user) is admitted.
  kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl label namespace "$NAMESPACE" --overwrite \
    pod-security.kubernetes.io/enforce=baseline pod-security.kubernetes.io/warn=restricted >/dev/null

  helm_install metrics-server metrics-server/metrics-server kube-system \
    --version "$METRICS_SERVER_CHART_VERSION" --set replicas="$replicas"

  helm_install aws-load-balancer-controller eks/aws-load-balancer-controller kube-system \
    --version "$LBC_CHART_VERSION" \
    --set clusterName="$CLUSTER" --set region="$AWS_REGION" --set vpcId="$vpc_id" \
    --set replicaCount="$replicas" \
    --set serviceAccount.create=true --set serviceAccount.name=aws-load-balancer-controller

  helm_install external-secrets external-secrets/external-secrets external-secrets \
    --version "$ESO_CHART_VERSION" --set installCRDs=true \
    --set serviceAccount.create=true --set serviceAccount.name=external-secrets

  helm registry logout public.ecr.aws >/dev/null 2>&1 || true
  helm_install karpenter oci://public.ecr.aws/karpenter/karpenter kube-system \
    --version "$KARPENTER_CHART_VERSION" \
    --set settings.clusterName="$CLUSTER" --set settings.interruptionQueue="$queue" \
    --set serviceAccount.create=true --set serviceAccount.name=karpenter \
    --set replicas="$replicas" \
    --set controller.resources.requests.cpu=250m --set controller.resources.requests.memory=512Mi \
    --set controller.resources.limits.memory=512Mi

  log "Applying the ClusterSecretStore"
  sed -e "s|__REGION__|$AWS_REGION|g" "$AWS_DIR/k8s/cluster-secret-store.yaml" | kubectl apply -f -
}

# ---------------------------------------------------------------------------------------------
compute_image_tag() {
  if [[ -n "${IMAGE_TAG:-}" ]]; then printf '%s' "$IMAGE_TAG"; return; fi
  local sha dirty=""
  sha="$(cd "$ROOT" && git rev-parse --short=8 HEAD)"
  # Uncommitted changes to the code that gets built: tag by a hash of them so the same working
  # tree always yields the same tag (ECR tags are immutable) and a different one gets a new tag.
  if [[ -n "$(cd "$ROOT" && git status --porcelain -- libs services gradle build.gradle settings.gradle gradle.properties)" ]]; then
    dirty="-d$( (cd "$ROOT" && git diff HEAD -- libs services gradle build.gradle settings.gradle gradle.properties; \
                 git ls-files --others --exclude-standard -- libs services | xargs -r cat 2>/dev/null) | sha1 | cut -c1-8)"
  fi
  printf '%s%s' "$sha" "$dirty"
}

stage_images() {
  step "Build and push container images"
  resolve_account
  local tag
  tag="$(compute_image_tag)"
  printf '%s' "$tag" >"$STATE_DIR/image_tag"
  log "Image tag: $tag"

  aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null

  # Decide which images still need building before paying for the compile.
  local todo=() svc
  for svc in "${SERVICES[@]}"; do
    if aws ecr describe-images --repository-name "$PROJECT/$svc" --image-ids "imageTag=$tag" >/dev/null 2>&1; then
      log "  $svc:$tag already in ECR"
    else
      todo+=("$svc")
    fi
  done
  if [[ ${#todo[@]} -eq 0 ]]; then log "Nothing to build."; return; fi

  log "Compiling all services once (first run downloads Gradle dependencies; expect 10-15 minutes)..."
  (cd "$ROOT" && docker build --target build -f deploy/aws/docker/Dockerfile -t "$NAME-build:latest" .)

  for svc in "${todo[@]}"; do
    log "  building $svc"
    (cd "$ROOT" && docker build -f deploy/aws/docker/Dockerfile --build-arg "SERVICE=$svc" \
        -t "$REGISTRY/$PROJECT/$svc:$tag" .) >/dev/null
    docker push "$REGISTRY/$PROJECT/$svc:$tag" >/dev/null
    log "  pushed $svc:$tag"
  done
}

# ---------------------------------------------------------------------------------------------
stage_database() {
  step "Database bootstrap (12 databases and roles on RDS)"
  export_terraform_vars "$(cat "$STATE_DIR/zone_id")"
  local rds
  rds="$(tfo rds_endpoint)"
  kubectl -n "$NAMESPACE" delete job db-bootstrap --ignore-not-found --wait=true >/dev/null
  sed -e "s|__NAMESPACE__|$NAMESPACE|g" -e "s|__RDS_HOST__|$rds|g" -e "s|__SECRET_PREFIX__|$NAME|g" \
    "$AWS_DIR/k8s/db-bootstrap.yaml" | kubectl apply -f -
  if ! kubectl -n "$NAMESPACE" wait --for=condition=complete job/db-bootstrap --timeout=900s; then
    kubectl -n "$NAMESPACE" describe externalsecret db-bootstrap-secrets | tail -15 || true
    kubectl -n "$NAMESPACE" logs job/db-bootstrap --tail=60 || true
    die "The database bootstrap Job did not complete (details above)."
  fi
  kubectl -n "$NAMESPACE" logs job/db-bootstrap --tail=3 || true
}

# ---------------------------------------------------------------------------------------------
cloudfront_prefix_list() {
  [[ "$RESTRICT_ALB_TO_CLOUDFRONT" == "true" ]] || return 0
  local id
  id="$(aws ec2 describe-managed-prefix-lists \
        --filters "Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing" \
        --query 'PrefixLists[0].PrefixListId' --output text 2>/dev/null || true)"
  if [[ -z "$id" || "$id" == "None" ]]; then
    warn "Could not find the CloudFront prefix list; the ALB will accept connections from anywhere."
    return 0
  fi
  printf '%s' "$id"
}

render_generated_values() {
  local out="$STATE_DIR/values-generated.yaml" tag googlesso=false pl
  tag="$(cat "$STATE_DIR/image_tag" 2>/dev/null || compute_image_tag)"
  [[ -n "${GOOGLE_CLIENT_ID:-}" ]] && googlesso=true
  pl="$(cloudfront_prefix_list)"
  cat >"$out" <<EOF
# Generated by deploy.sh from terraform output. Do not edit; do not commit.
global:
  sizeProfile: "$SIZE_PROFILE"
  region: "$AWS_REGION"
  registry: "$REGISTRY"
  repositoryPrefix: "$PROJECT"
  imageTag: "$tag"
  secretPrefix: "$NAME"
  domain: "$DOMAIN_NAME"
  shopUrl: "https://shop.$DOMAIN_NAME"
  adminUrl: "https://admin.$DOMAIN_NAME"
  apiHost: "api.$DOMAIN_NAME"
  ingressHosts: ["api.$DOMAIN_NAME", "shop.$DOMAIN_NAME", "admin.$DOMAIN_NAME"]
  vpcCidr: "$(tfo vpc_cidr)"
  albCertificateArn: "$(tfo alb_certificate_arn)"
  cloudfrontPrefixListId: "$pl"
  googleSso: $googlesso
  jwt:
    issuer: "https://auth.$DOMAIN_NAME"
  endpoints:
    rds: "$(tfo rds_endpoint)"
    redis: "$(tfo redis_endpoint)"
    kafka: "$(tfo kafka_bootstrap)"
  mail:
    host: "email-smtp.$AWS_REGION.amazonaws.com"
    sender: "no-reply@$DOMAIN_NAME"
karpenter:
  clusterName: "$CLUSTER"
  nodeRole: "$(tfo karpenter_node_role_name)"
EOF
  printf '%s' "$out"
}

stage_platform() {
  step "Deploy the platform (13 services, autoscaling, network policies, ingress)"
  resolve_account
  export_terraform_vars "$(cat "$STATE_DIR/zone_id")"
  local values
  values="$(render_generated_values)"
  helm upgrade --install ecommerce "$W_AWS/helm/ecommerce" --namespace "$NAMESPACE" \
    -f "$(winpath "$values")" --wait --timeout 25m \
    || { kubectl -n "$NAMESPACE" get pods; die "helm did not reach a healthy state. Inspect: kubectl -n $NAMESPACE describe pod <name>; kubectl -n $NAMESPACE logs <name>"; }
  kubectl -n "$NAMESPACE" get deploy
}

# ---------------------------------------------------------------------------------------------
stage_dns() {
  step "API DNS record (api.$DOMAIN_NAME -> load balancer)"
  local zone alb_name host alb_zone i
  zone="$(cat "$STATE_DIR/zone_id")"
  alb_name="${NAME}-alb"

  log "Waiting for the load balancer to be provisioned..."
  for i in $(seq 1 60); do
    host="$(kubectl -n "$NAMESPACE" get ingress gateway -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
    [[ -n "$host" ]] && break
    sleep 15
  done
  [[ -n "${host:-}" ]] || die "The ingress has no load balancer address after 15 minutes. Check: kubectl -n $NAMESPACE describe ingress gateway; kubectl -n kube-system logs deploy/aws-load-balancer-controller"
  alb_zone="$(aws elbv2 describe-load-balancers --names "$alb_name" --query 'LoadBalancers[0].CanonicalHostedZoneId' --output text)"

  cat >"$STATE_DIR/dns-change.json" <<EOF
{
  "Comment": "api record for $NAME",
  "Changes": [{
    "Action": "UPSERT",
    "ResourceRecordSet": {
      "Name": "api.$DOMAIN_NAME",
      "Type": "A",
      "AliasTarget": { "HostedZoneId": "$alb_zone", "DNSName": "$host", "EvaluateTargetHealth": true }
    }
  }]
}
EOF
  aws route53 change-resource-record-sets --hosted-zone-id "$zone" \
    --change-batch "file://deploy/aws/.state/$ENVIRONMENT/dns-change.json" >/dev/null
  log "api.$DOMAIN_NAME -> $host"
}

# ---------------------------------------------------------------------------------------------
upload_spa() {
  # upload_spa <dist-dir> <bucket> <distribution-id>
  local dist="$1" bucket="$2" dist_id="$3" f
  aws s3 sync "$dist" "s3://$bucket" --delete \
    --cache-control "public,max-age=31536000,immutable" \
    --exclude "index.html" --exclude "sw.js" --exclude "manifest.webmanifest" \
    --exclude "offline.html" --exclude "robots.txt" >/dev/null
  # Files whose name never changes must not be cached by browsers or CloudFront for long.
  for f in index.html sw.js manifest.webmanifest offline.html robots.txt; do
    [[ -f "$dist/$f" ]] && aws s3 cp "$dist/$f" "s3://$bucket/$f" --cache-control "no-cache" >/dev/null
  done
  aws cloudfront create-invalidation --distribution-id "$dist_id" --paths "/*" >/dev/null
}

stage_frontends() {
  step "Build and publish the storefront and admin apps"
  if [[ "$SKIP_FRONTENDS" == "true" ]]; then log "SKIP_FRONTENDS=true"; return; fi
  export_terraform_vars "$(cat "$STATE_DIR/zone_id")"
  local shop="https://shop.$DOMAIN_NAME" admin="https://admin.$DOMAIN_NAME" sso=false
  local shop_bucket admin_bucket shop_dist admin_dist
  [[ -n "${GOOGLE_CLIENT_ID:-}" ]] && sso=true
  shop_bucket="$(tfo spa_bucket_shop)"; admin_bucket="$(tfo spa_bucket_admin)"
  shop_dist="$(tfo distribution_shop)"; admin_dist="$(tfo distribution_admin)"

  # Both apps are served from the same origin as the API (CloudFront forwards /api/*), so the
  # storefront's relative /api calls and the refresh cookie are first-party.
  log "Storefront"
  (cd "$ROOT/apps/product-catalog" && npm ci --no-audit --no-fund \
    && VITE_AUTH_BASE_URL="$shop" VITE_ADMIN_URL="$admin" VITE_GOOGLE_SSO_ENABLED="$sso" npm run build)
  upload_spa "$W_ROOT/apps/product-catalog/dist" "$shop_bucket" "$shop_dist"

  log "Admin"
  (cd "$ROOT/apps/admin-product-catalog" && npm ci --no-audit --no-fund \
    && VITE_API_BASE_URL="$admin" VITE_APP_ORIGIN="$admin" npm run build)
  upload_spa "$W_ROOT/apps/admin-product-catalog/dist" "$admin_bucket" "$admin_dist"
}

# ---------------------------------------------------------------------------------------------
stage_retention() {
  step "Log retention"
  local g
  for g in $(aws logs describe-log-groups --log-group-name-prefix "/aws/containerinsights/$CLUSTER" \
             --query 'logGroups[].logGroupName' --output text 2>/dev/null); do
    aws logs put-retention-policy --log-group-name "$g" --retention-in-days 30 >/dev/null || true
  done
  log "Container Insights log groups keep 30 days."
}

# ---------------------------------------------------------------------------------------------
smoke_get() {
  # smoke_get <url> [must-contain] [must-not-contain]
  local body
  body="$(curl -fsS --max-time 20 "$1" 2>/dev/null)" || return 1
  [[ -z "${2:-}" || "$body" == *"$2"* ]] || return 1
  [[ -z "${3:-}" || "$body" != *"$3"* ]] || return 1
}

stage_smoke() {
  step "Smoke tests (through CloudFront, the way a browser reaches it)"
  local shop="https://shop.$DOMAIN_NAME" admin="https://admin.$DOMAIN_NAME" failed=0
  log "DNS and CloudFront can take several minutes to answer after a first deploy; retrying for up to 10 minutes."

  check() {
    local label="$1"; shift
    if retry 40 15 smoke_get "$@"; then log "  ok    $label"; else warn "  FAIL  $label ($1)"; failed=1; fi
  }
  check "storefront loads"               "$shop/"
  check "admin loads"                    "$admin/"
  check "gateway + auth reachable (JWKS)" "$shop/.well-known/jwks.json" '"keys"'
  check "catalog API responds"           "$shop/api/products?page=0&size=1"
  # SIMULATED would mean payments can be self-approved: the single most important check.
  check "payments run against Stripe, not the simulator" "$shop/api/payments/config" 'STRIPE' 'SIMULATED'

  if [[ $failed -ne 0 ]]; then
    die "One or more smoke tests failed. kubectl -n $NAMESPACE get pods / logs <pod>, and the CloudFront + ALB target health in the AWS console."
  fi
}

print_summary() {
  cat <<EOF

${C_GREEN}Deployed: $NAME${C_OFF}

  Storefront : https://shop.$DOMAIN_NAME
  Admin      : https://admin.$DOMAIN_NAME
  API        : https://shop.$DOMAIN_NAME/api   (also https://api.$DOMAIN_NAME, reachable via CloudFront only)

Do these once:
  1. Stripe  -> add a webhook endpoint  https://shop.$DOMAIN_NAME/api/payments/webhook
              (events payment_intent.succeeded, payment_intent.payment_failed) and make sure
              STRIPE_WEBHOOK_SECRET in config.env is its signing secret.
  2. Google  -> if you use Google sign-in, add redirect URIs
              https://shop.$DOMAIN_NAME/login/oauth2/code/google and
              https://admin.$DOMAIN_NAME/login/oauth2/code/google
  3. SES     -> new accounts are in the sandbox. Request production access in the SES console,
              otherwise only verified addresses receive email.
  4. Admin   -> self-registration is on and there is no seeded admin: register, then grant the
              admin role (see docs/guides/deploy-aws.md, "First admin user").
  5. SMS     -> no SMS provider is wired; one-time-code SMS is not delivered. Email works.
  6. Alerts  -> confirm the SNS email subscription if you set ALERT_EMAIL.

Use the cluster:   export KUBECONFIG="$STATE_DIR/kubeconfig"; kubectl -n $NAMESPACE get pods
Traces (Jaeger):   kubectl -n $NAMESPACE port-forward svc/jaeger 16686:16686
Tear it all down:  ./deploy/aws/destroy.sh
EOF
}
