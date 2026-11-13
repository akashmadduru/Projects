# Runbook: database dump committed to the repository

**Incident:** commit `5943ec7` (2026-11-12) added `db-snapshots/` with four `pg_dump` / `pg_dumpall`
files. The repository `akashmadduru/Projects` is **public**, and `version_v1` (the default branch)
was pushed on 2026-11-13. On 2026-11-14 both the branch tip and the commit URL served the files
(HTTP 200). There were 0 forks.

## What was exposed

| File | Content | Risk |
|---|---|---|
| `globals_*.sql` | SCRAM-SHA-256 verifiers for the roles `postgres`, `auth_service`, `product_service`, `inventory_service` | Offline password guessing. These are the local dev database's roles. AWS environments generate their own passwords (Terraform `random_password`), so production is not affected. |
| `auth_db_*.sql` | 1 user account (a personal Gmail address), its Argon2id password hash, 2 refresh-token hashes with IP address and user agent | Personal data is public. The password hash can be attacked offline, which matters if that password is reused anywhere. |
| `product_db_*.sql`, `inventory_db_*.sql` | 105 products, 415 images, 18 categories, schema history | No secrets. |

## Response checklist

| # | Step | Status (2026-11-14) |
|---|---|---|
| 1 | Stop tracking `db-snapshots/` and git-ignore it (also `*.dump`) | ✅ Done in the working tree. Takes effect on GitHub when pushed. |
| 2 | Detect it automatically: Gitleaks rules `postgres-dump`, `postgres-scram-verifier`, `argon2-password-hash` in `.gitleaks.toml` | ✅ Done. A scan of full history finds exactly the 13 secrets in `5943ec7` and nothing else; the working tree is clean. |
| 3 | Block it locally before it is pushed: `.githooks/pre-commit` | ✅ Done. Enable it per clone with `git config core.hooksPath .githooks`. |
| 4 | Make the safe path the easy path: `infra/scripts/db-snapshot.sh` | ✅ Done. Writes outside the repository and refuses paths inside it. Databases with personal data are dumped schema-only by default. |
| 5 | Bind Postgres to `127.0.0.1` (the superuser password is the known value `postgres`) | ✅ Done in `infra/docker-compose.yml`. Apply with `docker compose -f infra/docker-compose.yml up -d postgres`. |
| 6 | Change the exposed account's password, and anywhere else that password was used | ⏳ **Owner action.** Sign in → Account → Security → change password, then "Sign out other sessions". If the same password is used for the Gmail account or anywhere else, change it there first. |
| 7 | Rotate the four local Postgres role passwords | ⏳ Optional (local only). See below. |
| 8 | Remove the files from git history and force-push `version_v1` | ⏳ **Needs approval.** See below. |
| 9 | Ask GitHub to drop cached views of the old commit | ⏳ After step 8. See below. |

## Step 7: rotate local role passwords

Run once per role (`auth_service`, `product_service`, `inventory_service`), then put the new value in
`infra/.env` (`AUTH_DB_PASSWORD`, `PRODUCT_DB_PASSWORD`, `INVENTORY_DB_PASSWORD`) and restart that
service:

```bash
NEW=$(openssl rand -base64 24 | tr -d '/+=')
docker exec -i ecommerce-postgres psql -U postgres -c "ALTER ROLE auth_service PASSWORD '$NEW'"
# edit AUTH_DB_PASSWORD in infra/.env, then:
docker compose -f infra/docker-compose.yml up -d auth-service
```

## Step 8: purge from history

Only one commit added the files, and nothing has been forked. Rewriting changes the SHA of
`5943ec7` and of every commit after it (9 commits on `version_v1`). Anyone else with a clone must
re-clone.

```bash
# 1. Start from a clean working tree (commit or stash everything first).
git status --short

# 2. Drop the folder from every commit on every branch.
git filter-branch --force --index-filter \
  'git rm -r --cached --ignore-unmatch db-snapshots' --prune-empty -- --all

# 3. Remove the backup refs filter-branch leaves, expire reflogs, and garbage-collect.
git for-each-ref --format='%(refname)' refs/original/ | xargs -r -n1 git update-ref -d
git reflog expire --expire=now --all && git gc --prune=now --aggressive

# 4. Verify nothing still contains the files (both must print nothing).
git log --all --oneline -- db-snapshots
git rev-list --all | xargs -n1 git ls-tree -r --name-only | grep db-snapshots

# 5. Publish.
git push --force-with-lease origin version_v1
```

`stash@{0}` (2026-11-12) also contains the files. Stashes are never pushed, so it only affects the
local `.git`. To clean it, either drop it once its content is committed (`git stash drop stash@{0}`)
or keep it and accept a local copy. `git filter-branch` does not rewrite stashes.

## Step 9: GitHub cached views

After the force-push, GitHub can still serve the old commit by SHA until its own garbage collection
runs. Submit GitHub's "Remove sensitive data" support request with the repository name and the
commit SHA `5943ec7` (and the new SHA list if they ask), stating there are no forks.

## Taking snapshots from now on

```bash
./infra/scripts/db-snapshot.sh                       # ~/ecommerce-db-snapshots/<timestamp>/
SNAPSHOT_DIR=/somewhere/else ./infra/scripts/db-snapshot.sh
./infra/scripts/db-snapshot.sh --with-personal-data  # full data, still outside the repo
```

What it dumps by default:

| With data | Schema only |
|---|---|
| `product_db` (except `product.product_views`, which tracks sessions), `inventory_db` | `auth_db`, `user_db`, `cart_db`, `order_db`, `payment_db`, `promotion_db`, `notification_db`, `rating_db`, `seller_db`, `support_db` |

It never dumps role passwords (`pg_dumpall --globals-only`).
