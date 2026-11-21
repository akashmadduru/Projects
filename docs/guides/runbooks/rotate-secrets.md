# Runbook: rotate a secret (AWS)

All secrets live in AWS Secrets Manager and reach pods through External Secrets. Pods read secrets as
environment variables at start-up, so **a rotated value only takes effect after the pods restart.**

| Secret | Where | Extra step | Impact |
|---|---|---|---|
| Database passwords (`<service>_password`) | `<project>-<env>/db` | re-run the bootstrap Job so RDS matches | restart of every service you changed |
| `internal_api_token` | `<project>-<env>/app` | restart **all** services together | internal calls fail between old and new pods during the roll |
| Stripe or Razorpay keys, webhook secrets | `<project>-<env>/app` (set via `config.env`) | change `config.env`, `--only infra,platform` | payments only |
| Google client secret | `<project>-<env>/app` | same as Stripe | Google sign-in only |
| `jwt-keystore` (signing key) | `<project>-<env>/jwt-keystore` | **do not replace casually** | replacing it signs everybody out |
| `auth_data_key`, `seller_data_key` | `<project>-<env>/app` | **do not rotate** | existing 2FA secrets / bank numbers become unreadable |
| `vapid_public_key`, `vapid_private_key` | `<project>-<env>/app` | rarely; set them as a pair | every browser push subscription stops working until the customer re-enables notifications |

## Common case: a password or token

1. Change the value in Secrets Manager (console, or `aws secretsmanager put-secret-value`). For values
   Terraform generates (`/db`, `/app`), prefer `terraform apply -replace=...` on the matching
   `random_password` so state and the secret stay in step.
2. If it is a **database** password, update RDS first:

   ```bash
   ./deploy/aws/deploy.sh --only database
   ```

3. Wait for External Secrets to refresh the Kubernetes Secret (it checks hourly), or force it:

   ```bash
   kubectl -n ecommerce annotate externalsecret app-secrets force-sync=$(date +%s) --overwrite
   ```

4. Restart the pods that use it:

   ```bash
   kubectl -n ecommerce rollout restart deploy/<service>       # or: deploy --all
   ```

**Verify:** `kubectl -n ecommerce get pods` shows the new pods Ready, and
`kubectl -n ecommerce get secret app-secrets -o jsonpath='{.data.internal_api_token}' | base64 -d`
matches the new value.

## Rotating the JWT signing key (planned, zero downtime)

The key provider supports two keys at once. Add the new key to the keystore alongside the old one, set
`PLATFORM_JWT_KEYSTORE_ALIAS` to the new alias, deploy, and remove the old key only after every token signed with it has expired and every cache has refreshed: access tokens live 10 minutes and the gateway caches the published keys for 15 minutes (up to an hour if auth-service is down), so wait at least two hours. Refresh tokens are opaque database records, not signed with this key. This is not automated; do it deliberately and
test in a non-production environment first.

## If a secret has leaked

Rotate it immediately using the steps above and see
[leaked-database-dump](leaked-database-dump.md) for the incident pattern: assume the value was copied,
rotate everything it protected, and check the audit log for use.
