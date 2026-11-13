# ADR 0004 - AWS deployment on EKS, driven by one script

Status: accepted (implemented in `deploy/aws/`), not yet exercised against a live AWS account.

## Context

The platform ran only on a developer's machine through `infra/docker-compose.yml`. The audit
(`AUDIT_README.md`) found no deployment automation, no Kubernetes manifests, no infrastructure as
code, and several defects that block a safe production deployment. The target architecture is the
diagram supplied with the request: Route 53, CloudFront and WAF, S3-hosted SPAs, an ALB in front of the
gateway, EKS with Karpenter and autoscaling, RDS PostgreSQL, MSK, ElastiCache, Secrets Manager.

## Decisions

1. **One Terraform stack, Helm for workloads, bash for orchestration.** Terraform owns every AWS
   resource except those controllers create (the ALB, Karpenter-launched servers, EBS volumes). One
   Helm chart renders all 13 workloads from one values file. `deploy.sh` and `destroy.sh` are the only
   entry points and are idempotent and resumable.
2. **No Eureka in the cluster, and no service code changed for it.** With the Eureka client disabled
   Spring Cloud uses its built-in static discovery; a ConfigMap lists every service id (both
   spellings) against its Kubernetes Service. Verified by routing a real gateway container through it.
3. **State-bucket locking uses S3 native locking** (Terraform >= 1.10), not DynamoDB as in the diagram:
   one fewer resource, same guarantee.
4. **WAF on CloudFront rather than the ALB** so rate limits see the real client address; the ALB admits
   only CloudFront's address range.
5. **Kafka stays plaintext and unauthenticated inside the VPC** for now. Hand-built consumer factories
   ignore `spring.kafka.*` security settings; the brokers already accept TLS for a later client change.
6. **Traces go to in-cluster Jaeger, not X-Ray**, because Micrometer's random trace ids fall outside
   X-Ray's accepted timestamp window.
7. **Autoscaling is CPU-based HPA plus Karpenter.** KEDA lag-based scaling for the Kafka consumers was
   not added; those services are capped small instead.
8. **Single `prod` environment with a `lean` / `prod` size switch**, not separate dev/staging/prod.

## Code changes made because the deployment needed them

| Change | Why it was required |
|---|---|
| `StripeGateway` refuses to start under the `prod` profile without a Stripe key | otherwise a blank key silently enables SIMULATED mode and customers could mark their own orders paid |
| SMS text no longer logged; no dev mail sink in production | one-time codes were logged in plaintext and would have been emailed to an arbitrary domain |
| `JobLock` (Postgres advisory lock) around all 8 `@Scheduled` jobs | every replica ran every job; non-idempotent jobs would run N times |
| `RateLimitWebFilter` can read the client address from `X-Forwarded-For` (`trusted-proxy-hops`) | behind CloudFront and the ALB every anonymous user shared one rate-limit bucket |
| auth-service runs with `SERVER_FORWARD_HEADERS_STRATEGY=framework` (chart setting); CloudFront forwards `Host` | the Google sign-in callback was built from the internal host (`http://auth-service:8081`); reproduced in a container and fixed |
| The gateway's actuator is served on a pod-reachable management port in the chart | its `prod` profile binds actuator to 127.0.0.1:8081, unreachable by kubelet probes and the ALB health check |
| `notification-service` gets a `prod` profile | it had none, leaving a committed local database password as the production fallback |

## Consequences

- Positive: a repeatable, reviewable deployment; the first real run is `lean`, in a throwaway account.
- Negative: roughly 100 new files to maintain; pinned chart and module versions need periodic bumps.
- The Terraform state holds generated passwords and the Stripe/Google values; the state bucket is the
  sensitive asset and must be access-controlled.
- Not addressed (see `deploy/aws/README.md`, "Known limits"): Razorpay, SMS provider, an outbox for
  events, per-service internal credentials, S3 for review media, RDS Proxy.
