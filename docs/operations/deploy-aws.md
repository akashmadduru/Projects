# Deploying the platform to AWS

One command puts the whole platform on AWS, one command removes it.

```bash
cp deploy/aws/config.example.env deploy/aws/config.env     # then edit it
./deploy/aws/deploy.sh                                     # build, provision, deploy, verify
./deploy/aws/destroy.sh                                    # remove everything
```

Both are bash scripts. On Windows run them from **Git Bash** (installed with Git for Windows) or WSL.

## What you get

```
 Route 53 ─► CloudFront + WAF ─┬─► S3   shop.<domain>    React storefront
                               ├─► S3   admin.<domain>   Vue admin + seller portal
                               └─► /api/*, /oauth2/*, /login/oauth2/*, /.well-known/*
                                     └─► ALB (api.<domain>) ─► gateway ─► 12 services
 VPC (3 AZs)   public: ALB, NAT      private: EKS nodes      isolated: RDS, MSK, ElastiCache
 EKS + Karpenter (nodes on demand) · HPA per service · External Secrets ◄─ Secrets Manager
 RDS PostgreSQL 17 (12 databases) · MSK Kafka (3 brokers) · ElastiCache Redis · SES · ECR
```

Both web apps are served from the same origin as the API, so the refresh-token cookie is first-party
and no cross-origin setup is needed in the browser.

| Layer | Where it is defined |
|---|---|
| Infrastructure (VPC, EKS, RDS, Redis, Kafka, ECR, CloudFront, WAF, SES, IAM, secrets, alarms) | [terraform/](terraform/) |
| Cluster workloads (13 services, autoscaling, network policies, ingress, Karpenter pools) | [helm/ecommerce/](helm/ecommerce/) |
| Database bootstrap (12 databases and roles on RDS) | [k8s/db-bootstrap.yaml](k8s/db-bootstrap.yaml) |
| One image definition for every service | [docker/Dockerfile](docker/Dockerfile) |
| Orchestration | [deploy.sh](deploy.sh), [destroy.sh](destroy.sh), [lib/](lib/) |

## Before you start

You need an AWS account and credentials that can create IAM roles, VPCs, EKS, RDS, MSK and CloudFront
(an administrator is simplest for a first run), a domain you control, and a Stripe account.

Install these (Windows: `winget install ...`):

| Tool | Version | Windows |
|---|---|---|
| AWS CLI | v2 | `winget install Amazon.AWSCLI` |
| Terraform | >= 1.10 | `winget install Hashicorp.Terraform` |
| kubectl | recent | `winget install Kubernetes.kubectl` |
| Helm | >= 3.12 | `winget install Helm.Helm` |
| Docker | running daemon, >= 6 GB memory | Docker Desktop |
| Node.js | >= 20 (skip with `SKIP_FRONTENDS=true`) | `winget install OpenJS.NodeJS.LTS` |
| git, openssl, curl | | Git for Windows |

`deploy.sh` checks all of this first and tells you what is missing. Authenticate with `aws sso login`
or `aws configure` (set `AWS_PROFILE` in `config.env` for a named profile).

## First deployment

1. `cp deploy/aws/config.example.env deploy/aws/config.env` and fill in at least `DOMAIN_NAME` and the
   three `STRIPE_*` values. Everything else has a default. **Keep `SIZE_PROFILE=lean` for a first run.**
2. `./deploy/aws/deploy.sh --plan` shows what Terraform would create. This step does create the
   Terraform state bucket and the Route 53 zone (both nearly free); nothing else.
3. `./deploy/aws/deploy.sh`. If the domain has no hosted zone yet the script creates one, prints the
   four nameservers, and waits: set them at your registrar, then press Enter. (Certificates validate
   through DNS, so nothing can finish before this.)

The first run takes roughly 40 minutes for infrastructure (EKS, RDS, MSK and CloudFront are slow to
create) plus 10-15 minutes to compile the Java services. Re-runs are much faster: images that already
exist in ECR are not rebuilt, and Terraform only changes what changed.

When it finishes it runs smoke tests through CloudFront and prints the URLs and a short list of
one-time tasks (Stripe webhook, Google redirect URIs, SES sandbox).

### Stages

| Stage | What it does |
|---|---|
| `preflight` | tools, credentials, config, confirms the account before spending money |
| `backend` | Terraform state bucket (private, versioned, encrypted) and the DNS zone |
| `infra` | `terraform apply` |
| `keystore` | creates the JWT signing key in Secrets Manager once; never replaces it |
| `access` | writes a private kubeconfig under `deploy/aws/.state/` (your `~/.kube/config` is not touched) |
| `addons` | metrics-server, AWS Load Balancer Controller, External Secrets, Karpenter |
| `images` | compiles all services once, builds and pushes 13 images to ECR |
| `database` | creates the 12 databases and least-privilege roles on RDS |
| `platform` | `helm upgrade --install` of everything |
| `dns` | `api.<domain>` to the load balancer |
| `frontends` | builds both SPAs, uploads to S3, invalidates CloudFront |
| `retention` | 30-day log retention |
| `smoke` | end-to-end checks through CloudFront, including that payments are NOT in simulator mode |

Every stage is idempotent. `./deploy/aws/deploy.sh --from platform` resumes; `--only images,platform`
ships a new version; `--yes` removes prompts for CI.

## Everyday operations

```bash
export KUBECONFIG="$PWD/deploy/aws/.state/prod/kubeconfig"    # path uses your ENVIRONMENT name
kubectl -n ecommerce get pods
kubectl -n ecommerce logs deploy/order-service --tail=100
kubectl -n ecommerce port-forward svc/jaeger 16686:16686      # traces (in-memory, debugging aid)
```

**Ship a new version:** commit, then `./deploy/aws/deploy.sh --only images,platform,frontends`. Images
are tagged with the commit SHA (plus a hash of uncommitted changes), so every deploy is traceable.
Roll back with `helm -n ecommerce rollback ecommerce`.

**Scaling.** Each service has a CPU-based autoscaler (sizes in [values.yaml](helm/ecommerce/values.yaml):
for example gateway 3-12, order 2-8, payment 2-4) and Karpenter adds or removes servers to fit. Scale-down
waits five quiet minutes and never removes more than half the pods per minute.

- *Resize a service:* change its `hpa.min` / `hpa.max` in `values.yaml`, then `--only platform`.
- *Pin a service at a fixed replica count (manual scale, or pause autoscaling during an incident):*
  set that service's `hpa: { enabled: false }` and `replicas: N`, then `--only platform`. Set
  `hpa.enabled: true` again to hand control back.
- *Emergency, right now:* `kubectl -n ecommerce delete hpa order-service` then
  `kubectl -n ecommerce scale deploy/order-service --replicas=6`; the next `--only platform` run restores
  the autoscaler from the chart.
- *Database connection budget:* each pod holds up to `poolSize` (default 8) connections. Max pods x 8,
  summed over all services, must stay below the database's `max_connections` (about 450 on the lean
  instance). A CloudWatch alarm fires above 350. Lower `poolSize` or add RDS Proxy before raising limits.

**Rotate a secret:** change it in Secrets Manager (`<project>-<env>/app` or `/db`). External Secrets
refreshes the Kubernetes secret within an hour; restart the affected deployments to pick it up
(`kubectl -n ecommerce rollout restart deploy/<name>`). For a database password, re-run
`./deploy/aws/deploy.sh --only database` first so RDS matches.

**First admin user.** There is no seeded admin. Register on the admin site, then grant the role once:

```bash
export KUBECONFIG=...   # as above
PW=$(kubectl -n ecommerce get secret db-secrets -o jsonpath='{.data.master_password}' | base64 -d)
RDS=$(cd deploy/aws && terraform -chdir=terraform/envs/prod output -raw rds_endpoint)
kubectl -n ecommerce run psql --rm -it --restart=Never --image=public.ecr.aws/docker/library/postgres:17-alpine \
  --env=PGPASSWORD="$PW" --env=PGSSLMODE=require -- \
  psql -h "$RDS" -U dbadmin -d auth_db -c \
  "INSERT INTO auth.user_roles (user_id, role_id) SELECT u.id, 1 FROM auth.users u WHERE u.email='you@example.com' ON CONFLICT DO NOTHING;"
```

(role 1 = `ROLE_ADMIN`). Then sign out and in again so the new token carries the role.

## Cost

Rough monthly figures while the environment is running, from list prices as I understand them (not
checked against the current AWS price list; confirm in the AWS Pricing Calculator):

- `lean`: a few hundred USD. One NAT gateway, single-AZ `db.t4g.medium`, one `cache.t4g.small`, three
  `kafka.t3.small` brokers, small spot-heavy nodes.
- `prod`: well over a thousand USD. The three MSK brokers, the Multi-AZ database and the NAT gateways
  dominate.

`destroy.sh` is the way to stop paying.

## Removing everything

```bash
./deploy/aws/destroy.sh --dry-run     # shows the plan, changes nothing
./deploy/aws/destroy.sh               # type the environment name to confirm
```

Order matters and the script handles it: the load balancer, the servers Karpenter launched and the
EBS volumes are created by controllers inside the cluster, not by Terraform, so they are removed first
(otherwise the VPC cannot be deleted). Then the database (after a **final snapshot**), then
`terraform destroy`, then a sweep of anything controller-created that survived, which is reported
rather than ignored.

Kept unless you ask: the database snapshot, the Terraform state bucket (`--delete-state-bucket`), and
the DNS zone (`--delete-zone`; deleting it changes your nameservers). `--no-snapshot` skips the final
snapshot. Non-interactive use needs `--yes --confirm <project>-<env>` so a typo cannot delete the
wrong environment. The KMS key is scheduled for deletion in 7 days (the minimum AWS allows).

## Design decisions worth knowing

- **No Eureka in the cluster.** The services find each other through the Kubernetes Services. With the
  Eureka client off, Spring Cloud falls back to its built-in static discovery, fed by a ConfigMap
  ([discovery.yaml](helm/ecommerce/templates/discovery.yaml)) mounted into every pod. **No service code
  changed for this.** Tested with a real gateway container against a stub backend.
- **Actuator.** The gateway's `prod` profile serves actuator on `127.0.0.1:8081`, which Kubernetes
  probes and the ALB can never reach. The chart rebinds it to the pod IP and points the probes and the
  ALB health check at it; everything else keeps actuator on its main port. `/actuator` is not routed
  by CloudFront or the gateway, so it is not reachable from the internet.
- **Google sign-in host.** CloudFront forwards each site's own `Host` (shop / admin) to the API, the ALB
  certificate and listener rules cover all three hostnames, and auth-service honours `X-Forwarded-*`
  (`SERVER_FORWARD_HEADERS_STRATEGY=framework`). The callback is therefore
  `https://shop.<domain>/login/oauth2/code/google` (or `admin.`), which is what you register with
  Google. Without this the callback was built as `http://auth-service:8081/...` (reproduced and fixed in
  a container test).
- **WAF at CloudFront**, not the ALB: only there does it see the real client address. The ALB accepts
  connections only from CloudFront's address range (`RESTRICT_ALB_TO_CLOUDFRONT`), so the WAF cannot be
  bypassed by calling the API host directly.
- **Gateway rate limiting** behind CloudFront and the ALB keys on the client address taken from the
  right-hand end of `X-Forwarded-For` (`trusted-proxy-hops`). Without this every anonymous user would
  share the load balancer's address and one bucket.
- **Scheduled jobs** are guarded by a Postgres advisory lock ([JobLock](../../libs/common-web/src/main/java/com/acme/ecommerce/common/web/job/JobLock.java)),
  so running many replicas does not run a job many times.
- **Payments fail closed.** Under the `prod` profile `payment-service` refuses to start without a
  Stripe key, so the self-approve "simulate" endpoint cannot exist in production.
- **Secrets** live only in Secrets Manager and reach pods through External Secrets using EKS Pod
  Identity; no AWS key is stored in the cluster. Terraform *state* does contain the generated passwords
  and your Stripe/Google values: the bucket is private, versioned and encrypted, so limit who can read it.

## Known limits (read these)

1. **This has not been run against a real AWS account.** The Java builds and tests, the Dockerfile, the
   Helm chart (lint + render in both size profiles) and Terraform (`validate`) were verified locally;
   the gateway discovery, actuator and fail-closed behaviour were exercised in containers. The first
   real `deploy.sh` is the first end-to-end test: use `lean`, in a non-production account.
2. **Pinned versions** (Helm charts for Karpenter, the Load Balancer Controller, External Secrets and
   metrics-server; Terraform modules; MSK `3.8.x`; RDS `17`) were chosen from what I know and could not
   be checked against a live registry. If one is rejected, the error names it; bump it in
   [lib/stages.sh](lib/stages.sh) or the Terraform files.
3. **Kafka is unauthenticated and plaintext inside the VPC**, restricted by security group, like today.
   Several services build their Kafka consumers by hand with only a bootstrap address, so they cannot
   yet use TLS or SASL. The brokers already accept TLS on 9094.
4. **Razorpay is not part of this.** `payment-service` still uses Stripe.
5. **No SMS provider.** One-time-code SMS is not delivered in production (the dev "email the SMS to a
   sink mailbox" shortcut is disabled so codes are never mailed to an arbitrary domain, and codes are no
   longer written to logs). Email sign-in works.
6. **`rating-service` runs one replica**: it stores review images on its disk (an EBS volume). It needs
   an S3 storage backend before it can scale out.
7. **One shared `INTERNAL_API_TOKEN`** still protects `/internal/**` on every service. Network policies
   limit who can reach it; replace it with per-service credentials or mTLS next.
8. **Traces go to an in-cluster Jaeger with in-memory storage.** X-Ray was not used because Micrometer's
   random trace ids fall outside the time window X-Ray accepts, so most traces would be dropped.
9. **Topics created by the services** (`product.events`, `inventory.events`) request replication
   factor 1, so they are not replicated across the three brokers.
10. **No content-security-policy header** on the web apps (it needs a review of every external script the
    apps use). HSTS, frame, content-type, referrer and permissions headers are set.
11. **New SES accounts are sandboxed**: request production access in the SES console.
12. **No RDS Proxy.** See the connection budget above.

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `terraform apply` hangs on a certificate | the nameservers are not set at your registrar yet; set them and re-run |
| `payment-service` crash-loops with "STRIPE_SECRET_KEY is required" | intended: the Stripe secret is empty. Fix `config.env`, then `--only infra,platform` |
| pods `Pending` for several minutes on a first deploy | Karpenter is launching servers; `kubectl get nodeclaims` |
| `Cannot reach the Kubernetes API` | `KUBERNETES_API_ALLOWED_CIDRS` does not include your current public IP |
| ALB fails to create with a security-group rule error | set `RESTRICT_ALB_TO_CLOUDFRONT=false` and re-run `--only platform` |
| smoke test says SIMULATED | payment config was not applied; `kubectl -n ecommerce describe pod -l app.kubernetes.io/name=payment-service` |
| legitimate requests blocked with 403 | an AWS managed WAF rule matched; open the WAF's sampled requests in the console and set that rule to *count* in [modules/waf](terraform/modules/waf/main.tf) |
| image build killed | give Docker Desktop more memory (about 6 GB) |
| `terraform destroy` fails on a subnet/VPC | a controller-created resource survived; run `destroy.sh` again, it sweeps and retries |
