# auth-service

The platform's **identity provider**. Issues the RS256 access tokens every other service
trusts, and owns the rotating refresh-token chain that keeps sessions alive.

Two modules:

| Module | What it is |
|---|---|
| `auth-service-api` | Published contract: DTOs, `AuthErrorCode`, the `AuthClient` interface. No Spring Boot app, no entities. Consumers depend on this and nothing else. |
| `auth-service-app` | The deployable service. Blocking Spring MVC on **virtual threads**, not WebFlux. |

---

## The one rule that matters

**Every authentication path converges on `TokenService.issueTokenPair()`.**

Password login and Google login are two front doors into one token-issuance method. No
controller and no OAuth2 handler mints a token directly — and none of them *could*, because
`AccessTokenIssuer`, `RefreshToken` and `RefreshTokenRepository` are all package-private to
`com.acme.ecommerce.auth.token`. The rule is enforced by the compiler, not by convention.

`TokenConvergenceIntegrationTest` drives both front doors and asserts the resulting tokens are
structurally identical — same header, same claim names — differing only in `auth_method`. If
someone adds a second issuance path, that test goes red.

---

## User-snapshot cache (Phase 2 slice)

Every successful call to `TokenService.issueTokenPair()` — password login, refresh rotation, Google
login, the same convergence point described above — writes `user-snapshot:{userId}` to Redis:
`{id, email, displayName, roles, status}`, as JSON, via `UserSessionCache`.

**What it's for.** A read-side performance aid for a future consumer (e.g., an admin-facing "who is
this user" lookup) that would otherwise need a round-trip to `auth.users`. Nothing reads it today —
it exists ahead of that consumer, not because one exists yet.

**When it's written.** Inline, inside `issueTokenPair()`'s `@Transactional` method, *after* the
refresh token has already been persisted and the access token signed — but this is not the
after-commit discipline `ProductEventPublisher` uses in product-service. A cache write racing a
rollback is at worst a stale/orphaned entry that expires in 10 minutes, not a correctness bug, so it
does not need the same guarantee a Kafka publish does.

**TTL.** Equal to the access-token TTL (10 minutes), sourced from the exact same
`AuthProperties.Jwt.accessTokenTtl` config the access token issuer itself reads — not a second,
independently-configured constant that could drift from it.

**Fail open, always — never used for authZ.** `UserSessionCache.put` catches every exception, logs
at WARN, and never rethrows. A Redis outage must never turn a successful login into a 500; that
would make an optional cache a hard dependency, the same mistake the gateway's lazy JWKS fetch and
outage-tolerant token verification exist specifically to avoid elsewhere in this platform. Nothing
in the platform reads this cache to make an allow/deny decision — the JWT `roles` claim, verified
independently by every resource server, remains the sole authorization source. There is also no
explicit invalidation on role change or account disable in this iteration: a disabled account's
stale snapshot can still sit in cache for up to 10 minutes, but since nothing gates access on it,
that staleness cannot itself become an authorization bypass. `infra/docker-compose.yml` gates
auth-service's startup on Postgres and Eureka only — deliberately **not** on Redis, for the same
fail-open reason.

See [`docs/HLD-event-driven-inventory.md`](../architecture/design/HLD-event-driven-inventory.md) §7 and §9 for
the full consistency/security rationale.

---

## Contract for Phase 1D (gateway) and every future resource server

The gateway validates tokens **locally against JWKS**. It must not call `/api/auth/validate` on
the request path — that would put auth-service on the critical path of every request in the
platform.

**JWKS:** `GET /.well-known/jwks.json` — public, `Cache-Control: max-age=300`, public key
material only (`kty`, `kid`, `use`, `alg`, `n`, `e`). Every key in the keystore is published, so
a rotation can overlap.

**Access token claims:**

| Claim | Value | Notes |
|---|---|---|
| `iss` | `https://auth.acme-ecommerce.internal` | `auth.jwt.issuer` |
| `aud` | `ecommerce-platform` | `auth.jwt.audiences`. **Validate it** — without it any token this issuer minted is accepted anywhere. |
| `sub` | user UUID | |
| `exp` / `iat` / `nbf` | 10-minute TTL | |
| `jti` | UUID | enables a future deny-list |
| `email` | lower-cased | |
| `roles` | **UNPREFIXED** — `["ADMIN"]` | The DB stores `ROLE_ADMIN`; the prefix is stripped once, on the way out. `common-security`'s `RolesClaimAuthoritiesConverter` re-adds it. Emitting prefixed names here would produce `ROLE_ROLE_ADMIN` authorities and silently break every `hasRole(...)` rule. |
| `auth_method` | `password` \| `google` | |

Header: `alg=RS256`, `typ=JWT`, `kid` = RFC 7638 thumbprint.

> `typ` is **`JWT`, not `at+jwt`.** RFC 9068 prefers `at+jwt`, but Spring Security's
> `NimbusJwtDecoder` ships with a type verifier that accepts only `JWT` or an absent `typ`.
> Emitting `at+jwt` would require a custom verifier in every consumer, and the first one to
> forget it produces an outage that looks like a signature problem.

Use `common-security`'s `JwtDecoderBuilder` + `PlatformJwtAuthenticationConverter` so every
service interprets a token identically.

---

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/api/auth/register` | public | Gated by `auth.registration.enabled` (**false** by default). Grants `ROLE_VIEWER` only. |
| `POST` | `/api/auth/login` | public | Rate-limited per IP. |
| `POST` | `/api/auth/refresh` | cookie only | Reads the refresh token **only** from the HttpOnly cookie. |
| `POST` | `/api/auth/logout` | cookie | Always 204. Revokes the whole family. |
| `GET` | `/api/auth/me` | Bearer | Single query via `@EntityGraph`. |
| `POST` | `/api/auth/validate` | Bearer | Introspection. **Not** for the gateway. |
| `GET` | `/.well-known/jwks.json` | public | |
| `GET` | `/oauth2/authorization/google` | public | 404 unless Google credentials are configured. |

Refresh cookie: `HttpOnly; Secure; SameSite=Lax; Path=/api/auth`.

The refresh token is **never** in a response body and **never** in a redirect URL. After a Google
login the SPA lands on its callback with only a cookie set, and calls `/api/auth/refresh` to get
its first access token.

---

## Refresh rotation and reuse detection

```
presented token
     |
     +-- hash not found ............... 401 AUTH_REFRESH_INVALID, NO side effects
     |
     +-- revoked, rotated < 10s ago ... 401 AUTH_REFRESH_RACE, family SURVIVES, cookie KEPT
     +-- revoked, rotated >= 10s ago .. REUSE: revoke ENTIRE family, WARN + alert metric, 401
     +-- revoked for another reason ... 401 AUTH_REFRESH_INVALID (logout etc. — not an attack)
     |
     +-- expired ..................... mark EXPIRED, 401, family survives
     |
     +-- valid
           +-- compare-and-set lost ... 401 AUTH_REFRESH_RACE, family survives
           +-- compare-and-set won .... issue child, 200
```

Two things are load-bearing and must not be "simplified":

1. **The compare-and-set is not a read-then-write.** `UPDATE ... WHERE id=? AND revoked_at IS
   NULL` with an affected-row check. Read-then-write lets two concurrent refreshes both mint a
   child in one family; the next request from either client then looks like reuse, and the
   mechanism built to catch thieves starts revoking honest users.
2. **`family_absolute_expires_at` is copied verbatim, never recomputed.** Recomputing it on
   rotation turns the 30-day cap into an unbounded session — and nothing fails, no test goes red,
   the control simply ceases to exist.

Sliding window 14d, absolute family cap 30d, grace window 10s. Raw tokens are never stored, only
their SHA-256 hash (unsalted is correct: the input is 256 bits of CSPRNG output, so a KDF would
add ~100ms to the hottest path and buy nothing).

---

## Run it locally

```bash
# 1. keystore (once) — NEVER generated at startup
./infra/scripts/generate-keystore.sh -o infra/secrets/jwt-keystore.p12 -p devkeystorepw -a jwt-signing

# 2. Postgres (POSTGRES_PORT override if 5432 is taken)
POSTGRES_PORT=15432 docker compose -f infra/docker-compose.yml up -d postgres

# 2b. Redis — OPTIONAL, for the user-snapshot cache only (see above). Login/refresh
# work normally without it; UserSessionCache.put fails open.
docker compose -f infra/docker-compose.yml up -d redis

# 3. the service — Flyway migrates on startup
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/auth_db \
SPRING_DATASOURCE_USERNAME=auth_service \
SPRING_DATASOURCE_PASSWORD=auth_service_local_pwd \
PLATFORM_JWT_KEYSTORE_PATH=infra/secrets/jwt-keystore.p12 \
PLATFORM_JWT_KEYSTORE_PASSWORD=devkeystorepw \
PLATFORM_JWT_KEYSTORE_ALIAS=jwt-signing \
REDIS_HOST=localhost \
REDIS_PORT=6379 \
./gradlew :services:auth-service:auth-service-app:bootRun
```

Google login is **optional**: with `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` unset the OAuth2 leg
is simply not installed and everything else works normally. To enable it, create an OAuth 2.0
Client ID (Web application) in Google Cloud, add `http://localhost:8081/login/oauth2/code/google`
as an authorised redirect URI, and export both variables.

### Tests

Testcontainers needs a reachable Docker socket. On Colima:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew :services:auth-service:auth-service-app:test
```

Postgres, not H2 — the schema uses `citext`, `inet` and partial indexes, and the rotation
compare-and-set depends on Postgres' READ COMMITTED re-evaluation semantics. An in-memory
substitute would pass while the real behaviour was broken.

---

## Security posture

**Implemented**

- Argon2id (OWASP m=19456, t=2, p=1) behind `DelegatingPasswordEncoder`, so the algorithm can be
  migrated without a mass password reset.
- No user enumeration: unknown user and wrong password return byte-identical bodies, and
  `DaoAuthenticationProvider` runs a dummy Argon2 verify so they cost the same wall-clock time.
  A social-only account (no `user_credentials` row) is indistinguishable from a missing one.
- Per-account lockout (5 failures, exponential backoff capped at 15m). **This is the real
  brute-force control** — it lives in the shared database and works with any replica count. The
  Bucket4j per-IP throttle is in-memory, per-instance, and a soft control only.
- Google linking gated strictly on the IdP's `email_verified=true`; matching is on the OIDC `sub`,
  never on the email address.
- Cookie-based (HMAC-signed) `AuthorizationRequestRepository`, so the OAuth2 flow survives a
  second replica. The default `HttpSession` one breaks the moment there is one.
- Redirect allowlist with exact matching (no prefix/suffix match) against open redirects.
- RFC 9457 problem+json everywhere, including from filters and the resource-server entry point.

**Known gaps / accepted trade-offs**

1. **CSRF is disabled.** No forms, no session; the only cookie-borne credential is `SameSite=Lax;
   Path=/api/auth`, so a cross-site POST does not carry it. Revisit if a browser-rendered form is
   ever added.
2. **`AUTH_EMAIL_TAKEN` (409) confirms an address is registered.** Accepted for an internal
   console; the alternative needs an email pipeline that does not exist yet. Revisit before this
   endpoint is exposed publicly.
3. **`AUTH_ACCOUNT_LOCKED` reveals that an account exists.** Deliberate: it is only reachable
   after several failures against a real address, and the alternative is a user who cannot log in
   with a password they know is correct.
4. **Rate limiting is per-instance.** Redis is now in the stack (Phase 2 slice, user-snapshot cache),
   but distributed rate limiting on top of it has not been built — this gap is unchanged.
5. **Housekeeping runs on every replica.** Harmless (idempotent DELETE) but wasteful; move behind
   a lock if the table ever grows enough for the sweep to be expensive.
6. **`user-snapshot:{userId}` has no invalidation on role change or account disable.** It relies
   solely on its 10-minute TTL. Acceptable only because the cache is never read on an authorization
   path — see "User-snapshot cache (Phase 2 slice)" above.

---

## Key rotation (zero downtime)

`JwtKeyProvider` publishes **all** keystore entries and signs with `auth.jwt.active-kid` (accepts
a keystore alias or an RFC 7638 thumbprint).

1. Add key B to the keystore, redeploy — B is published, A still signs.
2. Wait for every consumer's JWKS cache to expire (≥ 5 minutes).
3. Flip `auth.jwt.active-kid` to B — new tokens use B, tokens already issued under A still verify.
4. After the longest access-token TTL, remove A.

Skipping step 1 or 3 is what produces the classic "everyone got logged out during the rotation".
