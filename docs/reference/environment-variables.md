# Environment variables

> **Generated file. Do not edit by hand.** Regenerate with `node deploy/local/scripts/env-reference.mjs`.
> The variable list, defaults, "required" flags and the **Set in** column are read from the code and
> deployment files; the one-line descriptions are curated in
> [env-descriptions.json](env-descriptions.json). CI fails if this file is stale or a variable has no
> description (`--check`).

How to read the tables:

- **Default**: the value the service uses when the variable is unset. **none** means there is no default.
- **Required**: `yes, in prod` means at least one service refuses to start in the `prod` profile without it.
- **Set in**: where this repository sets it for you: `.env` = the local template
  [deploy/local/.env.example](../../deploy/local/.env.example), `compose` = set directly in
  [docker-compose.yml](../../deploy/local/docker-compose.yml), `helm` = the AWS chart
  ([values.yaml](../../deploy/aws/helm/ecommerce/values.yaml) and templates). `-` means nothing sets it: you
  only need it to override a default.
- **Secret**: a lock marks a value that must never be committed or logged. On AWS these come from
  Secrets Manager, not from `config.env`.

**Precedence (highest wins):** real environment variable, then the ConfigMap mounted at `/config` on AWS,
then the packaged `application.yml`. A variable set to an empty string is treated as set but blank.

On AWS you normally set nothing here yourself: `deploy.sh` renders these from
[deploy/aws/config.example.env](../../deploy/aws/config.example.env). Use this page when overriding a
service-level setting (add it under that service's `env:` in `values.yaml`). Related:
[cloud setup](../guides/cloud-setup.md), [third-party setup](../guides/integrations.md),
[how a value travels](../architecture/diagrams/17-configuration-flow.md).

## Platform

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `ADMIN_BASE_URL` | seller | `http://localhost:3001` |  | compose + helm | Public origin of the admin / seller portal. Used in seller emails. |
| `MANAGEMENT_SERVER_ADDRESS` | gateway (Helm) | _Spring default_ |  | helm | Bind address for a separate management port. The gateway defaults to `127.0.0.1` in `prod`, which Kubernetes probes cannot reach; the chart sets `0.0.0.0`. |
| `MANAGEMENT_SERVER_PORT` | all services (Helm) | _Spring default_ |  | helm | Port for actuator (health, metrics). Equal to the service port for every service except the gateway, whose `prod` profile uses a separate port 8081. |
| `RATING_MEDIA_DIR` | rating | `/data/media` |  | compose + helm | Directory where review images are stored. On AWS this is an EBS volume, which is why rating-service runs one replica. |
| `SERVER_FORWARD_HEADERS_STRATEGY` | auth (Helm) | _Spring default_ |  | helm | Makes auth-service honour `X-Forwarded-Host/Proto`, so the Google sign-in callback URL uses the public host instead of the internal one. |
| `SERVER_PORT` | all services | _per service_ |  | helm | Port the service listens on. Each service has its own default (gateway 8080, auth 8081, product 8082 ... support 8092). |
| `SPRING_CONFIG_ADDITIONAL_LOCATION` | all services (Helm) | _Spring default_ |  | helm | Extra config directory. On AWS it points at `/config/`, where the discovery ConfigMap is mounted. |
| `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` | all services (Helm) | _Spring default_ |  | helm | How long a pod may spend finishing in-flight requests on shutdown. Must be shorter than the pod termination grace period. |
| `SPRING_PROFILES_ACTIVE` | all services | _Spring default_ |  | compose + helm | Spring profile. `prod` turns on production behaviour: no default passwords, secure cookies, payments refuse to run in simulated mode. Set by Helm on AWS. |
| `STOREFRONT_BASE_URL` | auth, cart, notification, seller, support, user | `http://localhost:3000` |  | .env + helm | Public origin of the storefront. Used in links inside emails (verify, reset password, order, referral) and as a CORS/redirect reference. |

## Identity and tokens

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `AUTH_ALLOWED_REDIRECT_URIS` | auth | `http://localhost:5173/auth/callback` |  | .env + helm | Comma-separated exact URLs the SPAs may be redirected to after sign-in. No wildcards. |
| `AUTH_COOKIE_SECURE` | auth | `false` |  | .env + helm | Marks the refresh-token cookie `Secure` (HTTPS only). `true` everywhere except local HTTP. |
| `AUTH_DATA_KEY` 🔒 | auth | `""` |  | .env + helm | AES-256 key (base64, 32 bytes) that encrypts two-factor secrets at rest. Blank: the service starts, but enabling two-factor fails. Losing or changing it locks existing 2FA users out. |
| `AUTH_DEFAULT_REDIRECT_URI` | auth | `http://localhost:5173/auth/callback` |  | .env + helm | Redirect used when the caller does not name one. |
| `AUTH_JWT_AUDIENCES` | all services | `ecommerce-platform` |  | .env + helm | Token audience (`aud`). Same rule as the issuer. |
| `AUTH_JWT_ISSUER` | all services | `https://auth.acme-ecommerce.internal` |  | .env + helm | Token issuer (`iss`). Must be identical in auth-service, the gateway and every resource server, or every request fails with `invalid_token`. |
| `AUTH_RATE_LIMIT_LOGIN_CAPACITY` | auth | `10` |  | compose | Login attempts per window per client (in-memory, per instance). |
| `AUTH_RATE_LIMIT_REFRESH_CAPACITY` | auth | `60` |  | compose | Token refreshes per window per client (in-memory, per instance). |
| `AUTH_REGISTRATION_ENABLED` | auth | `false` |  | .env + helm | Allows self-service sign-up. Default `false`; the local template and the AWS chart turn it on so you can register the first admin. Turn it off again once your accounts exist. |
| `AUTH_STATE_COOKIE_SECRET` 🔒 | auth | `""` |  | helm | Signs the OIDC `state` cookie. Must be the same on every auth-service replica. |
| `AUTH_TRUST_FORWARDED_FOR` | auth | `false` |  | - | Read the client IP from `X-Forwarded-For` for login throttling and device alerts. Only safe behind the gateway, which overwrites it. |
| `GOOGLE_CLIENT_ID` | auth | `""` |  | .env + helm | Google OAuth client id. Blank disables Google sign-in. |
| `GOOGLE_CLIENT_SECRET` 🔒 | auth | `""` |  | .env + helm | Google OAuth client secret. Blank disables Google sign-in. |
| `INTERNAL_API_TOKEN` 🔒 | all services | `""` |  | .env + helm | Shared secret for service-to-service `/internal/**` calls (`X-Internal-Token`). One value for every service; the same compromise-everything caveat applies. |
| `PLATFORM_JWT_KEYSTORE_ALIAS` | auth | `""` |  | compose + helm | Alias of the key used for signing. May be blank while the keystore has one key; set it during a key rotation. |
| `PLATFORM_JWT_KEYSTORE_PASSWORD` 🔒 | auth | `""` |  | compose + helm | Password of the keystore. |
| `PLATFORM_JWT_KEYSTORE_PATH` | auth | `deploy/local/secrets/jwt-keystore.p12` |  | compose + helm | Path of the PKCS#12 keystore holding the JWT signing key. Mounted, never baked into an image. |
| `SELLER_DATA_KEY` 🔒 | seller | `""` | yes | .env + helm | AES-256 key (base64, 32 bytes) that encrypts sellers' bank account numbers. **seller-service refuses to start without it**, or with a key that is not exactly 32 bytes. |

## Database

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | all services (Helm) | _Spring default_ |  | helm | Maximum database connections per pod (the code default is 10; the AWS chart uses 8). Pods x this value, summed over all services, must stay below the database `max_connections`. |
| `SPRING_DATASOURCE_PASSWORD` 🔒 | all services | _dev value only; none in prod_ | yes, in prod | compose + helm | Password of that role. **No default in the `prod` profile.** On AWS it comes from Secrets Manager (`<project>-<env>/db`). |
| `SPRING_DATASOURCE_URL` | all services | `jdbc:postgresql://localhost:5432/au...`, `jdbc:postgresql://localhost:5432/ca...` (varies) |  | compose + helm | JDBC URL of the service's own database. On AWS it ends in `?sslmode=require` (RDS refuses unencrypted connections). |
| `SPRING_DATASOURCE_USERNAME` | all services | `auth_service`, `cart_service` (varies) |  | compose + helm | Database role of the service (`<name>_service`), created by the database bootstrap. |

## Messaging and cache

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | all services | `localhost:9092` |  | compose + helm | Kafka brokers (`host:port,host:port`). On AWS the MSK plaintext listener, reachable only from the cluster. |
| `REDIS_HOST` | auth, gateway | `localhost` |  | compose + helm | Redis host: auth-service user cache and the gateway rate limiter. |
| `REDIS_PORT` | auth, gateway | `6379` |  | compose + helm | Redis port. |
| `SPRING_DATA_REDIS_SSL_ENABLED` | auth, gateway (Helm) | _Spring default_ |  | helm | Use TLS to Redis. ElastiCache has in-transit encryption on, so this is `true` on AWS. |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | all services (Helm) | _Spring default_ |  | compose + helm | Same brokers for the shared audit-event publisher, which reads the Spring name. |

## Payments

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `STRIPE_PUBLISHABLE_KEY` | payment | `""` |  | .env + helm | Stripe publishable key, handed to the browser. |
| `STRIPE_SECRET_KEY` 🔒 | payment | `""` | yes | .env + helm | Stripe secret key. Blank means SIMULATED mode locally; under the `prod` profile payment-service refuses to start without it. |
| `STRIPE_WEBHOOK_SECRET` 🔒 | payment | `""` |  | .env + helm | Signing secret of the Stripe webhook endpoint. |

## Email, SMS and push

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `MAIL_HOST` | notification | `localhost` |  | .env + helm | SMTP server. Locally the Mailpit container; on AWS `email-smtp.<region>.amazonaws.com` (SES). |
| `MAIL_PASSWORD` 🔒 | notification | `""` |  | .env + helm | SMTP password. |
| `MAIL_PORT` | notification | `1025` |  | .env + helm | SMTP port (587 with STARTTLS, 465 with SSL). |
| `MAIL_USERNAME` | notification | `""` |  | .env + helm | SMTP user. On AWS, the SES SMTP credentials Terraform creates. |
| `NOTIFICATION_SENDER` | notification | `no-reply@acme.local` |  | .env + helm | From address of outgoing mail. Must belong to a verified SES identity on AWS. |
| `NOTIFICATION_SERVICE_URL` | auth | `""` |  | - | Direct URL of notification-service. Blank resolves it through service discovery. |
| `NOTIFICATION_SMS_MAILBOX_DOMAIN` | notification | `""` |  | helm | Dev-only: SMS text is also emailed to `sms+<digits>@<domain>` so it shows up in Mailpit. **Blank in production**, which disables the sink; there is no SMS provider, so SMS is not delivered. |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | notification | _Spring default_ |  | compose + helm | SMTP authentication on/off. `true` with a real provider. |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE` | notification | _Spring default_ |  | compose + helm | Implicit TLS (port 465). |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | notification | _Spring default_ |  | compose + helm | Upgrade the SMTP connection with STARTTLS (port 587). |
| `VAPID_PRIVATE_KEY` 🔒 | notification | `""` |  | .env | Browser-push private key. Blank: generated on first start. |
| `VAPID_PUBLIC_KEY` | notification | `""` |  | .env | Browser-push public key. Blank: generated on first start and stored in the database. |
| `VAPID_SUBJECT` | notification | `mailto:admin@acme.local` |  | .env + helm | Contact (`mailto:`) sent to push services. |

## Edge, CORS and rate limits

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `GATEWAY_CORS_ALLOWED_ORIGINS` | gateway | `http://localhost:5173` | yes, in prod | .env + helm | Comma-separated exact browser origins allowed to call the API. No wildcards (the refresh cookie needs credentials). **No default in `prod`.** |
| `GATEWAY_RATE_LIMIT_ENABLED` | gateway | `true` |  | - | Turns the gateway's per-user / per-IP request limiter on or off. |
| `GATEWAY_RATE_LIMIT_READS` | gateway | `300` |  | compose + helm | Reads (GET) allowed per minute per user or IP. |
| `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS` | gateway (Helm) | _Spring default_ |  | helm | Number of trusted proxies in front of the gateway (CloudFront + ALB = 2). The client IP is read that many entries from the right of `X-Forwarded-For`. 0 = use the socket address. |
| `GATEWAY_RATE_LIMIT_WRITES` | gateway | `30` |  | compose + helm | Writes (POST/PUT/PATCH/DELETE) allowed per minute per user or IP. |

## Service discovery

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `CART_JWKS_SERVICE_ID` | cart | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `CART_JWKS_URI` | cart | `""` |  | helm | Fixed URL of auth-service's public keys, used by cart to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `EUREKA_CLIENT_ENABLED` | all services | `true` |  | helm | Register with and read from Eureka. `false` on AWS, where discovery is a Kubernetes ConfigMap plus Services. |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` | all services | `http://localhost:8761/eureka/` |  | compose | Eureka server URL (local Compose only). |
| `EUREKA_INSTANCE_HOSTNAME` | eureka-server | **none** | yes, in prod | - | Hostname the Eureka server advertises. No default in `prod`. |
| `GATEWAY_JWKS_SERVICE_ID` | gateway | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `GATEWAY_JWKS_URI` | gateway | `""` |  | helm | Fixed URL of auth-service's public keys, used by gateway to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `INVENTORY_JWKS_SERVICE_ID` | inventory | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `INVENTORY_JWKS_URI` | inventory | `""` |  | helm | Fixed URL of auth-service's public keys, used by inventory to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `NOTIFICATION_JWKS_SERVICE_ID` | notification | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `NOTIFICATION_JWKS_URI` | notification | `""` |  | helm | Fixed URL of auth-service's public keys, used by notification to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `ORDER_JWKS_SERVICE_ID` | order | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `ORDER_JWKS_URI` | order | `""` |  | helm | Fixed URL of auth-service's public keys, used by order to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `PAYMENT_JWKS_SERVICE_ID` | payment | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `PAYMENT_JWKS_URI` | payment | `""` |  | helm | Fixed URL of auth-service's public keys, used by payment to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `PRODUCT_JWKS_SERVICE_ID` | product | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `PRODUCT_JWKS_URI` | product | `""` |  | helm | Fixed URL of auth-service's public keys, used by product to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `PROMOTION_JWKS_SERVICE_ID` | promotion | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `PROMOTION_JWKS_URI` | promotion | `""` |  | helm | Fixed URL of auth-service's public keys, used by promotion to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `RATING_JWKS_SERVICE_ID` | rating | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `RATING_JWKS_URI` | rating | `""` |  | helm | Fixed URL of auth-service's public keys, used by rating to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `SELLER_JWKS_SERVICE_ID` | seller | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `SELLER_JWKS_URI` | seller | `""` |  | helm | Fixed URL of auth-service's public keys, used by seller to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `SUPPORT_JWKS_SERVICE_ID` | support | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `SUPPORT_JWKS_URI` | support | `""` |  | helm | Fixed URL of auth-service's public keys, used by support to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |
| `USER_JWKS_SERVICE_ID` | user | `AUTH-SERVICE` |  | - | Service id used to find auth-service when no fixed JWKS URL is set (default `AUTH-SERVICE`). |
| `USER_JWKS_URI` | user | `""` |  | helm | Fixed URL of auth-service's public keys, used by user to verify tokens. Set it on AWS (`http://auth-service:8081/.well-known/jwks.json`); blank resolves auth-service through discovery. |

## Business rules

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `ORDER_RISK_AVG_MIN_ORDERS` | order (Compose) | _Spring default_ |  | compose | Minimum past orders before the "unusually large order" check applies (Compose default 3). |
| `ORDER_RISK_AVG_MULTIPLIER` | order (Compose) | _Spring default_ |  | compose | How many times the customer's average an order may be before it is flagged (Compose default 10). |
| `ORDER_RISK_VELOCITY_THRESHOLD` | order (Compose) | _Spring default_ |  | compose | Orders in a short window that trigger a velocity risk flag (Compose default 8). Mapped by Spring onto `order.risk.*`. |
| `USER_REFERRAL_REWARD_COINS` | user | `100` |  | - | Coins awarded for a successful referral. |

## Observability

| Variable | Used by | Default | Required | Set in | What it does |
|---|---|---|---|---|---|
| `OTEL_TRACES_ENDPOINT` | all services | `http://localhost:4318/v1/traces` |  | compose + helm | OTLP/HTTP trace endpoint. Jaeger locally and in the AWS chart. |
| `TRACING_SAMPLING_PROBABILITY` | all services | `0.1` |  | compose + helm | Fraction of requests traced (0 to 1). 0.1 in production, 1.0 in local Compose. |

## Frontend build variables (Vite)

Set when the app is **built**, not at runtime: they are compiled into the JavaScript bundle. Locally they go in each app's own `.env`; on AWS `deploy.sh` sets them from your domain.

| Variable | App | What it does |
|---|---|---|
| `VITE_ADMIN_URL` | product-catalog | Link from the storefront footer to the admin site. |
| `VITE_API_BASE_URL` | admin-product-catalog | Origin of the API the admin app calls. On AWS it is the admin site itself (CloudFront forwards `/api/*`). |
| `VITE_APP_ORIGIN` | admin-product-catalog | Public origin of the admin app, used for OAuth redirects. |
| `VITE_AUTH_BASE_URL` | product-catalog | Origin the storefront uses to start Google sign-in (`/oauth2/authorization/google`). On AWS the storefront origin. |
| `VITE_GOOGLE_SSO_ENABLED` | product-catalog | Shows the "Continue with Google" button. `deploy.sh` sets it from whether `GOOGLE_CLIENT_ID` is filled in. |

## Deploy script settings (`deploy/aws/config.env`)

Read by `deploy.sh` / `destroy.sh`. Commented-out lines in the template are optional.

| Setting | Template value | What it does |
|---|---|---|
| `AWS_REGION` | `ap-south-1` | Region for everything except CloudFront certificates and WAF (always us-east-1). |
| `AWS_PROFILE` | `""` | Named AWS CLI profile; blank uses the default credential chain. |
| `PROJECT` | `ecommerce` | Name prefix for every resource. |
| `ENVIRONMENT` | `prod` | Environment name; with PROJECT it names the environment and is the word `destroy.sh` asks you to type. |
| `DOMAIN_NAME` | `example.com` | Root domain you control. Serves `shop.`, `admin.` and `api.` subdomains. |
| `SIZE_PROFILE` | `lean` | `lean` = cheapest working topology; `prod` = full high availability. |
| `ALERT_EMAIL` | `""` | Address that receives CloudWatch alarms (AWS emails a confirmation link). |
| `STRIPE_SECRET_KEY` 🔒 |  | **Required.** Stripe secret key; payment-service refuses to start in production without it. |
| `STRIPE_PUBLISHABLE_KEY` | `""` | **Required.** Stripe publishable key. |
| `STRIPE_WEBHOOK_SECRET` 🔒 |  | **Required.** Signing secret of the Stripe webhook endpoint. |
| `GOOGLE_CLIENT_ID` | `""` | Optional. Blank disables Google sign-in. |
| `GOOGLE_CLIENT_SECRET` 🔒 |  | Optional. Blank disables Google sign-in. |
| `KUBERNETES_API_ALLOWED_CIDRS` | `0.0.0.0/0` | Comma-separated CIDRs allowed to reach the Kubernetes API. Narrow it for production. |
| `RESTRICT_ALB_TO_CLOUDFRONT` | `true` | Only CloudFront may connect to the load balancer, so the WAF cannot be bypassed. |
| `IMAGE_TAG` | `""` | Image tag override; default is the git commit (plus a hash of uncommitted changes). |
| `SKIP_FRONTENDS` | `false` | `true` skips building and uploading the two web apps. |

