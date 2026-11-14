# Cloud setup: what to configure before the first deploy

Audience: the person who will run `./deploy/aws/deploy.sh`. This guide covers everything that has to
exist or be decided **before** that command, and where each value goes. The command itself is in
[deploy-aws](deploy-aws.md); every variable is in the
[environment variable catalogue](../reference/environment-variables.md).

> **Status:** the scripts, Terraform, Helm chart and the commands below are written but have never been
> run against a real AWS account. Cloud console screens change; for exact clicks follow the linked
> vendor documentation and use this page for the **values** you must supply. Items marked *(check)* are
> ones I could not verify and you should confirm in the console.

## 1. Checklist

Work top to bottom. Each row says how to confirm it is done.

| # | Step | Confirm with |
|---|---|---|
| 1 | AWS account, a non-production one for the first run | `aws sts get-caller-identity` shows the account you expect |
| 2 | Credentials with enough permission (section 2) | the same command succeeds |
| 3 | Region chosen (section 3) | `echo $AWS_REGION` or the value in `config.env` |
| 4 | EC2 vCPU and Elastic IP quotas are enough (section 4) | the two `aws service-quotas` commands below |
| 5 | A budget alarm exists (section 5) | it is listed in the Billing console |
| 6 | A domain you control (section 6) | you can edit its nameservers at the registrar |
| 7 | Stripe account and keys (section 7) | you have `sk_`, `pk_` and `whsec_` values |
| 8 | Google sign-in client, optional (section 8) | you have a client id and secret |
| 9 | Alert email chosen (section 9) | an address you can read |
| 10 | Tools installed (section 10) | `deploy.sh --plan` passes its preflight |
| 11 | `config.env` filled in (section 11) | `./deploy/aws/deploy.sh --plan` completes |

## 2. AWS account and permissions

- Use a **separate account or a sandbox** for the first run. A full teardown is automated
  ([destroy.sh](deploy-aws.md#removing-everything)), but an empty account removes any doubt.
- Sign in with the AWS CLI. Either IAM Identity Center (`aws configure sso`, then `aws sso login`) or
  access keys (`aws configure`). Put a named profile in `AWS_PROFILE` in `config.env` if you use one.
  See the [CLI configuration guide](https://docs.aws.amazon.com/cli/latest/userguide/cli-chap-configure.html).
- **Permissions.** The simplest, and what I recommend for the first run, is the `AdministratorAccess`
  managed policy. A narrower policy is not provided: Terraform creates IAM roles, a VPC, EKS, RDS, MSK,
  ElastiCache, S3, CloudFront, WAF, ACM, Route 53, SES, Secrets Manager, KMS, ECR, SQS and EventBridge
  (for Karpenter), SNS and CloudWatch, so a least-privilege policy would be long and must be maintained
  alongside the Terraform.
- Do not use the account root user for day-to-day work.

## 3. Region

`AWS_REGION` defaults to `ap-south-1` (Mumbai). Pick the region closest to your customers **and** check
that it offers Amazon MSK, EKS and SES *(check)*. Two things are always created in `us-east-1`
regardless of your choice, because AWS requires it: the CloudFront certificate and the CloudFront WAF
rules. Nothing for you to do.

## 4. Service quotas (new accounts are often limited)

Brand-new accounts frequently start with low EC2 limits, and the cluster launches servers on demand, so a
low vCPU quota shows up as pods stuck in `Pending`. Check before you deploy:

```bash
# Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) vCPUs   (quota code L-1216C47A)
aws service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A --query Quota.Value
# All Standard Spot Instance Requests vCPUs                       (quota code L-34B43A08)
aws service-quotas get-service-quota --service-code ec2 --quota-code L-34B43A08 --query Quota.Value
# Elastic IPs per region: one per NAT gateway (1 for lean, 3 for prod)  (quota code L-0263D0A3)
aws service-quotas get-service-quota --service-code ec2 --quota-code L-0263D0A3 --query Quota.Value
```

The quota codes are from memory *(check: the command errors if a code is wrong; look the quota up by
name in the Service Quotas console instead)*. As a guide (my estimate, not measured): the lean profile needs roughly 20-30 vCPU of headroom,
and the autoscaler is capped at 80 vCPU (general pool) plus 40 (critical pool); request an increase if
your limits are lower.

## 5. Spending guardrail

Create a budget alarm before anything that costs money exists: Billing console -> Budgets -> create a
monthly cost budget with an email alert at 80% (see the
[Budgets documentation](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-create.html)).
The lean profile is expected to cost a few hundred USD a month and the prod profile well over a thousand
(rough list-price estimates, see [Cost](deploy-aws.md#cost)). `destroy.sh` is how you stop paying.

## 6. Domain and DNS

You need a domain you control, for example `example.com`. The platform is served on three hostnames,
created for you:

| Hostname | Serves |
|---|---|
| `shop.<domain>` | storefront |
| `admin.<domain>` | admin and seller portal |
| `api.<domain>` | the API (reachable through CloudFront only) |

`deploy.sh` creates a Route 53 hosted zone for the domain if none exists, prints four nameservers, and
**waits**. Set those four nameservers at your registrar and press Enter. Certificates are validated
through DNS, so the deployment cannot continue until delegation works. Propagation can take minutes to
hours. Check with `nslookup -type=NS <domain>`.

Using a subdomain of a domain hosted elsewhere (for example `platform.example.com`) works the same way:
set `DOMAIN_NAME=platform.example.com` and delegate that name.

## 7. Stripe (required)

`payment-service` refuses to start in production without real Stripe keys, so these are mandatory.

1. Create or open a Stripe account and stay in **test mode** for the first deployment.
2. Developers -> API keys: copy the **Publishable key** (`pk_test_...`) and **Secret key**
   (`sk_test_...`). See [Stripe API keys](https://docs.stripe.com/keys).
3. Developers -> Webhooks -> add endpoint:
   - URL: `https://shop.<your-domain>/api/payments/webhook`
   - events: `payment_intent.succeeded` and `payment_intent.payment_failed`
   The URL does not need to be live yet. Copy the endpoint's **Signing secret** (`whsec_...`).
4. Put the three values in `config.env` as `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`,
   `STRIPE_WEBHOOK_SECRET`.

Going live means swapping in the `sk_live_` / `pk_live_` keys and a live-mode webhook, then running
`./deploy/aws/deploy.sh --only infra,platform`. Read the test-mode checklist in
[integrations](integrations.md#3-payment-gateway-stripe) first: payments have never been run against real
Stripe in this codebase.

## 8. Google sign-in (optional)

Leave `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` blank to run with password and one-time-code login
only. To enable it, in the Google Cloud console create an **OAuth client ID of type "Web application"**
(see [Google's guide](https://developers.google.com/identity/protocols/oauth2/web-server)) and add
exactly these **Authorized redirect URIs**:

```
https://shop.<your-domain>/login/oauth2/code/google
https://admin.<your-domain>/login/oauth2/code/google
```

Copy the client id and secret into `config.env`. The consent screen must be configured; while it is in
"Testing" only listed test users can sign in. No JavaScript origins are needed.

## 9. Email and alerts

- **Outgoing email** uses Amazon SES, set up by Terraform: your domain is verified through DNS records it
  creates, and SMTP credentials are generated and stored in Secrets Manager. You supply nothing.
- **New SES accounts are in the sandbox:** mail is delivered only to verified addresses. Request
  production access in the SES console (Account dashboard) before real customers register. This is a
  manual AWS review and cannot be automated.
- **Alarms** (database CPU, storage, connections; Redis CPU) are emailed to `ALERT_EMAIL`. AWS sends a
  confirmation link you must click; until you do, no alert arrives.
- **SMS:** no provider is wired, so one-time-code SMS is not delivered in production. Email works.

## 10. Local tools

Required: AWS CLI v2, Terraform 1.10 or newer, kubectl, Helm 3.12 or newer, Docker (running, with about
6 GB of memory), Node.js 20 or newer (unless `SKIP_FRONTENDS=true`), git, openssl and curl. On Windows
run the scripts from Git Bash. The install commands are in [deploy-aws](deploy-aws.md#before-you-start);
`deploy.sh` checks them all and names anything missing.

Find your public IP for the next section with `curl https://checkip.amazonaws.com`.

## 11. Fill in `config.env`

```bash
cp deploy/aws/config.example.env deploy/aws/config.env
```

`config.env` is gitignored and holds real secrets once filled in. Minimum to change:

| Setting | Value |
|---|---|
| `DOMAIN_NAME` | your domain (section 6) |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET` | from section 7 |
| `AWS_REGION` / `AWS_PROFILE` | sections 2 and 3 |
| `ALERT_EMAIL` | section 9 |
| `KUBERNETES_API_ALLOWED_CIDRS` | `<your-ip>/32`, so only you can reach the Kubernetes API. The default `0.0.0.0/0` is open to the internet (it still needs AWS credentials). If your IP changes, update this and run `--only infra`. |
| `SIZE_PROFILE` | keep `lean` for the first run |

Every setting, with its meaning, is in the
[deploy script settings](../reference/environment-variables.md#deploy-script-settings-deployawsconfigenv)
table. Then run `./deploy/aws/deploy.sh --plan`.

## 12. Where each value ends up

| You provide it in | It becomes | Visible to |
|---|---|---|
| `config.env`: Stripe, Google | Secrets Manager `<project>-<env>/app`, then a Kubernetes Secret | only the pods that reference it |
| `config.env`: domain, size | Terraform variables and Helm values | everyone who can read the cluster or the state |
| generated by Terraform | Secrets Manager `/app` and `/db` (database passwords, internal token, data keys, state cookie secret) | pods; Terraform state (private, encrypted) |
| generated by `deploy.sh` | Secrets Manager `/jwt-keystore` (JWT signing key) | auth-service only |

The full path is drawn in [diagram 17](../architecture/diagrams/17-configuration-flow.md).

## 13. Overriding a service-level setting

For anything not in `config.env` (a rate limit, a pool size, a business rule), find it in the
[catalogue](../reference/environment-variables.md), add it under that service's `env:` in
[values.yaml](../../deploy/aws/helm/ecommerce/values.yaml), and run `./deploy/aws/deploy.sh --only
platform`. Never put a secret there: add secrets to Secrets Manager and reference them with `secretEnv`.

## 14. After the first deploy

The script prints a short list. In order: add the Stripe webhook if you skipped it, add the Google
redirect URIs, request SES production access, grant the first admin
([runbook](runbooks/first-admin-user.md)), and confirm the alert subscription. Rotating a secret later is
covered in the [runbook](runbooks/rotate-secrets.md).
