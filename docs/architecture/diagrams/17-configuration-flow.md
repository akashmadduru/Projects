# 17. Configuration flow: how a setting reaches a running service

Last checked against the code: 2026-11-15.

One value, such as the Stripe secret key, takes a different route locally and on AWS. Both end as an
environment variable in the container; a packaged `application.yml` default is the fallback underneath.

```mermaid
flowchart LR
    subgraph LOCAL["Local: Docker Compose"]
        ENV[".env<br/>(gitignored, from .env.example)"]
        KS1["secrets/jwt-keystore.p12<br/>(generate-keystore.sh)"]
        COMPOSE["docker-compose.yml<br/>environment: ${VAR}"]
        ENV --> COMPOSE
        KS1 -. bind mount .-> C1
        COMPOSE --> C1["service container<br/>env vars"]
    end

    subgraph AWS["AWS: deploy.sh"]
        CFG["config.env<br/>(gitignored, from config.example.env)<br/>domain, size, Stripe, Google"]
        TF["Terraform<br/>TF_VAR_* from the environment"]
        SM[("Secrets Manager<br/>&lt;project&gt;-&lt;env&gt;/app<br/>&lt;project&gt;-&lt;env&gt;/db")]
        KS2[("Secrets Manager<br/>&lt;project&gt;-&lt;env&gt;/jwt-keystore<br/>created once by deploy.sh")]
        OUT["terraform output<br/>RDS, Redis, Kafka, ARNs"]
        VAL["values-generated.yaml<br/>(rendered by deploy.sh)"]
        HELM["Helm chart<br/>values.yaml + generated values"]
        CM["ConfigMap<br/>discovery-config at /config"]
        ESO["External Secrets<br/>(EKS Pod Identity)"]
        K8S["Kubernetes Secrets<br/>app-secrets, db-secrets,<br/>jwt-keystore"]
        POD["pod<br/>env vars + /config + /run/secrets"]

        CFG --> TF
        TF -- generates passwords and keys --> SM
        CFG -- Stripe / Google values --> SM
        TF --> OUT --> VAL --> HELM
        CFG --> HELM
        HELM -- plain settings --> POD
        HELM --> CM --> POD
        SM --> ESO
        KS2 --> ESO
        ESO --> K8S -- secretKeyRef / volume --> POD
    end

    YML["application.yml in the image<br/>${VAR:default}"]
    C1 --> YML
    POD --> YML
```

## Precedence (highest wins)

1. Environment variable on the container (`.env` via Compose, or the Helm `env:` / Secrets).
2. The ConfigMap mounted at `/config` (AWS only; it carries the service-discovery map).
3. The packaged `application.yml` default.

## Reading the diagram

- **Plain settings** (ports, URLs, sizing, feature flags) travel `config.env` -> Helm -> pod.
- **Secrets** never pass through Helm values. Terraform generates them (or reads them from your
  `config.env` for Stripe and Google), stores them in Secrets Manager, and External Secrets copies them
  into Kubernetes Secrets that pods reference by name.
- **The JWT signing key** is created by `deploy.sh` directly in Secrets Manager, so the private key never
  enters Terraform state.
- Terraform state still contains the generated passwords and your Stripe/Google values: keep the state
  bucket private (it is, by default) and limit who can read it.

Related: [environment variable catalogue](../../reference/environment-variables.md),
[cloud setup](../../guides/cloud-setup.md), [deploy-aws](../../guides/deploy-aws.md).
