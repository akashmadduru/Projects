# Settings

`settings.json` defines workspace-wide defaults consumed by every agent and command:

- **Model selection** — default and auto-recommendation rules, sourced from
  `../prompts/model-selection.md`. Every command must present this prompt.
- **Token budget** — default and auto-recommendation rules, sourced from
  `../prompts/token-budget.md`. Every command must present this prompt.
- **Agent/command/template/prompt directories** — the canonical locations Polymath and
  all commands resolve against.
- **Output contract** — the required section list every deliverable must include,
  sourced from `../prompts/output-format.md`.
- **Rules** — the workspace's non-negotiable engineering rules (see root `README.md`
  "Rules" section for the full rationale behind each).
- **Permissions** — a conservative default allow/deny list for Bash and file tools.
  Adjust `allow`/`deny` to match your project's actual toolchain (e.g. add `Bash(poetry *)`
  if the backend uses Poetry instead of pip, or `Bash(cargo *)` if a Rust service is
  introduced). Destructive operations require explicit confirmation regardless of the
  allow list.

## Customizing

This file is intended to be edited per-project. Common adjustments:

- Change `model.default` to `"sonnet"` if your team prefers deeper reasoning by default
  and is less cost-sensitive.
- Add project-specific `permissions.allow` entries for your package manager, test
  runner, and deployment CLI.
- Extend `rules` if your organization has additional non-negotiable conventions (e.g.
  `"requireConventionalCommits": true`).

Nothing in this workspace requires editing `settings.json` to function — defaults are
safe out of the box.
