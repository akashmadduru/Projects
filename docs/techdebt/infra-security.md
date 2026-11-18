# Tech Debt: Infra and Security

Index: [README](README.md)

<a id="td-sec-01"></a>
## TD-SEC-01 Real GOOGLE_CLIENT_SECRET in local `deploy/local/.env`
- **Severity / Effort:** High / S
- **Where:** `deploy/local/.env` (a non-empty `GOOGLE_CLIENT_SECRET`). Gitignored (`.gitignore` lines 32-34, `.env.example` excepted), so not in the repo.
- **Risk:** The secret was pasted into plaintext and handled during this work; anyone with the machine or a shared copy has it.
- **Fix:** Rotate the secret in Google Cloud Console and update `deploy/local/.env`; use a secrets manager outside local dev. Confirm with `git log -p -- deploy/local/.env` that it was never committed.

<a id="td-sec-04"></a>
## TD-SEC-04 Open registration by default
- **Severity / Effort:** Med / S
- **Where:** `deploy/local/docker-compose.yml` (`AUTH_REGISTRATION_ENABLED:-true`).
- **Risk:** Anyone can create accounts. Email verification exists since Phase 6 but is advisory (see [TD-P6-10](phase6.md#td-p6-10)). Fine for dev, not for production.
- **Fix:** Default false outside dev.

<a id="td-sec-05"></a>
## TD-SEC-05 Manual DB script for existing volumes
- **Severity / Effort:** Med / M
- **Where:** `deploy/local/scripts/create-customer-dbs.sql`.
- **Risk:** Init scripts run only on an empty volume. Existing environments must run the script by hand, or user/cart/order services fail to start. No migration runner for infra.
- **Fix:** A one-shot `db-bootstrap` compose service that runs idempotent role/db creation on every `up`.

<a id="td-sec-07"></a>
## TD-SEC-07 rating-service not runnable
- **Severity / Effort:** Med / M
- **Where:** included in `settings.gradle` (lines 53-54) but absent from `deploy/local/docker-compose.yml` and gateway routes.
- **Risk:** `/api/ratings` is unreachable end to end; ratings UI cannot work.
- **Fix:** Add compose block, DB bootstrap and gateway route modeled on inventory-service.

<a id="td-sec-08"></a>
## TD-SEC-08 No account-linking UX
- **Severity / Effort:** Low / M
- **Where:** auth-service `FederatedIdentityResolver` (unchanged) and the storefront login page.
- **Risk:** A Google login whose email matches a password account is resolved by auth-service as-is; the storefront does not explain or confirm linking.
- **Fix:** Confirm-linking step requiring the existing password.

<a id="td-sec-14"></a>
## TD-SEC-14 Database dumps with credential material remain in git history
- **Severity / Effort:** High / S
- **Where:** `db-snapshots/*_20261112-011622.sql`, added in commit `5943ec7` (2026-11-12) to the public repository. Deleted from the branch and pushed in `38a92a3`/`97cd7e2` (2026-11-14): the branch tip no longer serves them (HTTP 404), but `5943ec7` still does.
- **Risk:** Anyone with a clone can read SCRAM verifiers for 4 Postgres roles (including the `postgres` superuser), an Argon2id password hash, and refresh-token hashes with IP and user agent.
- **Fix:** Follow the [runbook](../guides/runbooks/leaked-database-dump.md): change the exposed account password, optionally rotate the local role passwords, purge history with `git filter-branch` (rehearsed on a clone: 13 commits rewritten, tip tree otherwise identical) and force-push, then ask GitHub to drop cached views. Prevention (Gitleaks rules, pre-commit hook, `db-snapshot.sh`) is already in place.
