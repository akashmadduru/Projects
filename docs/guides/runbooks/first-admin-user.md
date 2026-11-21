# Runbook: create the first admin user (AWS)

**When:** right after the first deployment. There is no seeded admin, and the admin site, seller
approval and every `/api/admin/**` endpoint need `ROLE_ADMIN`.

**Needs:** the cluster kubeconfig that `deploy.sh` wrote, and an account you registered yourself.

1. Open `https://admin.<your-domain>` (or the storefront) and **register** the account that should be
   admin. Self-registration is on in the AWS chart (`AUTH_REGISTRATION_ENABLED=true`).
2. Point `kubectl` at the cluster:

   ```bash
   export KUBECONFIG="$PWD/deploy/aws/.state/<environment>/kubeconfig"     # for example prod
   ```

3. Grant the role (role id 1 is `ROLE_ADMIN`; the other ids are 2 catalog manager, 3 viewer, 4 seller,
   5 support, 6 finance):

   ```bash
   PW=$(kubectl -n ecommerce get secret db-secrets -o jsonpath='{.data.master_password}' | base64 -d)
   RDS=$(aws rds describe-db-instances --db-instance-identifier <project>-<environment>-postgres \
     --query 'DBInstances[0].Endpoint.Address' --output text)        # for example ecommerce-prod-postgres
   kubectl -n ecommerce run psql --rm -it --restart=Never \
     --image=public.ecr.aws/docker/library/postgres:17-alpine \
     --env=PGPASSWORD="$PW" --env=PGSSLMODE=require -- \
     psql -h "$RDS" -U dbadmin -d auth_db -c \
     "INSERT INTO auth.user_roles (user_id, role_id) SELECT u.id, 1 FROM auth.users u WHERE u.email='you@example.com' ON CONFLICT DO NOTHING;"
   ```

4. Sign out and in again so the new token carries the role.
5. **Turn self-registration off** once the accounts you need exist: set `AUTH_REGISTRATION_ENABLED` to
   `"false"` under `auth-service` -> `env:` in `deploy/aws/helm/ecommerce/values.yaml` and run
   `./deploy/aws/deploy.sh --only platform`.

**Verify:** `https://admin.<your-domain>` loads the dashboard instead of a permission error.

**If it fails:** `INSERT 0 0` means no account has that email: list them with `SELECT email FROM auth.users;` and match the stored spelling exactly. A connection timeout usually means RDS cannot be reached from the cluster: check that the database security group allows port 5432 from the cluster node security group.

## Local development (docker compose)

Self-registration is on in `deploy/local/.env`, so register at http://localhost:3000 (or the admin app
at http://localhost:5173), then grant the role from the Postgres container:

```bash
docker exec -it ecommerce-postgres psql -U postgres -d auth_db -c \
  "INSERT INTO auth.user_roles (user_id, role_id) SELECT u.id, 1 FROM auth.users u WHERE u.email='you@example.com' ON CONFLICT DO NOTHING;"
```

Sign out and in again. This replaces the old SQL seed, which inserted into `auth.users` before Flyway
had created the table (TD-SEC-06).
