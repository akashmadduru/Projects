# Branching and release tagging

Pipeline details: [ci-cd.md](ci-cd.md).

## Branches

| Branch | Purpose | Deploys to | Protection |
|---|---|---|---|
| `dev` | Integration. Feature branches merge here. | `dev` environment | PR + `ci-gate` |
| `staging` | Release candidate. Only merged from `dev`. | `staging` environment | PR + `ci-gate` |
| `master` | Production. Only merged from `staging`. | `production` environment (reviewers) | PR + `ci-gate` + reviewers |

Work flows one way: `feature/*` or `fix/*` -> `dev` -> `staging` -> `master`. Hotfixes branch from `master`, merge to `master`, then are merged back to `staging` and `dev`.

`version_v1` is the legacy working branch the three environment branches were cut from.

## Tags (SemVer)

| Tag | Created on | Effect |
|---|---|---|
| `vX.Y.Z-rc.N` | `staging` | CD publishes images tagged `:vX.Y.Z-rc.N` to the staging environment |
| `vX.Y.Z` | `master` | CD publishes images tagged `:vX.Y.Z` to the production environment |

Every build also gets an immutable `:sha-<short>`; that is what Promote uses for rollback.

Create tags with the helper, which enforces the branch rule, a clean tree, and that the commit is already on GitHub:

```bash
infra/scripts/release-tag.sh v1.2.0-rc.1 --push   # on staging
infra/scripts/release-tag.sh v1.2.0 --push        # on master
```

Tags that do not match these two shapes are rejected by the workflow (`prep` job fails).

## Release checklist
1. Merge `dev` -> `staging` by PR; confirm the staging deploy.
2. Tag `vX.Y.Z-rc.N` on `staging` for any candidate you want to name.
3. Merge `staging` -> `master` by PR (production reviewers approve the environment).
4. Tag `vX.Y.Z` on `master`.
5. Rollback: Actions -> Promote -> `staging -> production` with `source_tag` set to a previous `sha-...` or version tag.
