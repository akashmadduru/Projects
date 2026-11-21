# Branch protection (Owner, one-time, GitHub web UI)

OpenForge's local hooks stop agents from committing to or pushing protected branches, but only GitHub
can stop *anyone* from merging unreviewed work. Set this once per protected branch (`dev`, `main` or
`master`, and `version_v1` while it is in use).

GitHub → repository **Settings → Rules → Rulesets → New branch ruleset**:

| Setting | Value |
|---|---|
| Ruleset name | `protect-<branch>` |
| Enforcement | Active |
| Target branches | Include by pattern: `dev`, `main`, `master`, `version_v1` |
| Restrict deletions | on |
| Block force pushes | on |
| Require a pull request before merging | on; required approvals **1**; dismiss stale approvals on new commits **on**; require approval of the most recent push **on** |
| Require status checks to pass | on; require branches to be up to date **on**; checks: `OpenForge gate`, `CI gate` (from ci.yml), `CodeQL` / `Semgrep` jobs from security.yml as you choose |
| Require conversation resolution | on |
| Bypass list | empty (not even admins), or only you for emergencies |

Why each matters:
- **1 required approval + approval of the most recent push**: GitHub never counts the PR author's own
  approval, so the human approval cannot come from whoever opened the PR, and any push after approval
  needs a fresh approval.
- **OpenForge gate**: the PR must carry independent agent reviews for the code it contains.
- **Block force pushes**: history on protected branches cannot be rewritten.

Check it worked: open a test PR from a scratch branch with no work record. `OpenForge gate` passes
(not a forge branch) but merging is blocked until you approve; push a `forge/F-...` branch without
reviews and `OpenForge gate` fails.

If you later install the GitHub CLI (`winget install GitHub.cli`, then `gh auth login`), the CEO can
read check results and prepare PRs, but merging stays yours.
