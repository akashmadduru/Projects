# Lessons learned

One entry per lesson, newest first. Written by the CEO at step 11 of each objective.
Format: `## <date> <work id>: <lesson in one line>` then what happened, why, and the rule we now follow.

## 2026-11-19 bootstrap: agents, hooks and the `agent` setting load only at session start
Agents created in `.claude/agents/` during a session cannot be spawned in that session ("Agent type not
found"), hooks added to `.claude/settings.json` do not fire until the next session (a commit on `main`
in a scratch repo went through), and `"agent": "forge-ceo"` applies only to new sessions. Skills, by
contrast, were picked up live. Rule: after changing agents, hooks or settings, start a new session
before running an objective, and never emulate a role with a generic agent: that silently drops the
role's tool limits and the hooks keyed on `agent_type`.

## 2026-11-19 bootstrap: hook parsers must skip git's global options
`git -C <dir> -c user.email=… commit` bypassed the first version of forge-guard, which expected the
subcommand right after `git`/`-C`. Rule: the guard tokenises the command and skips global options;
the regression test covers `-c`, `-C`, `--no-pager` and `cd … &&` forms.

## 2026-11-19 F-20261119-01: verify runtime assumptions end to end before design, not in review
Three review rounds each found a new major in the same place: where the client IP comes from. Round 1:
the CEO assumed the gateway overwrites X-Forwarded-For (it appends). Round 2: Spring Boot's Kubernetes
default (NATIVE) rewrites the header in Tomcat. Round 3: Spring Cloud Gateway 5.0.2 strips X-Forwarded-*
unless trusted-proxies is set. Each was cheap to check in code and expensive to find in review (about
half the objective's tokens were rework). Rule: discovery lists every environment assumption (proxies,
headers, platform defaults, library versions) with the file or source that proves it; unproven ones are
blocking questions; any feature that depends on request metadata gets a test through the real hop
(gateway → service), not only a unit or MockMvc test.

## 2026-11-19 F-20261119-01: the size limit counts the wrong lines
Production code was about 420 lines; tests were 1,400. The class M limit of 600 counted both, so a
well-tested change failed the gate and a poorly tested one would pass. Proposed rule change (Owner):
apply the limit to production lines only, and report test and doc lines separately.

## 2026-11-19 F-20261119-01: agents break operational rules they were told
An engineer ran two Docker builds in parallel despite "one build at a time" in project.md and its brief
(they collided on index.lock and corrupted files). Rule: put the lock in the script, not the prompt
(gradle.sh should take a host-level lock), the same way branch rules live in hooks.
- 2026-11-19 (process) The one-build rule is now enforced by a host-wide lock in `.openforge/scripts/gradle.sh`
  (mkdir lock, stale-owner takeover, 30 min wait) instead of prompt text.
- 2026-11-19 (F-20261119-01 r4) forge-verify --changed runs only Gradle/vitest, so a CI-checked generated doc
  (`deploy/local/scripts/env-reference.mjs --check`) went stale unnoticed. Proposal G-4: forge-verify runs the
  env-reference check when `docs/reference/env-descriptions.json`, any `application.yml` or `config.env` changes.
- 2026-11-19 (F-20261119-03) Verify output that the CEO records only in log.md is invisible to reviewers, who then
  flag "verify not run". Record verify output in evidence.md every time. And when a rule changes mid-objective
  (G-3 size rule), restate any earlier condition that used the old rule, or reviewers enforce the old one.
- 2026-11-19: Reviewer and security agents are read-only (no Write tool). The brief must ask them to return the verdict JSON in the reply, and the CEO saves it verbatim. Asking them to write the file wastes a turn.
- 2026-11-19: forge-cto, forge-architect and the reviewers have Write but no Edit, so an 'append' to an existing file turns into a full overwrite. A CTO attempt to blank design.md was blocked. Brief these roles to return rulings in the reply, or to write new files only.
- 2026-11-20: Engineers added new env vars twice (F-06, F-07) without running env-reference --check. Briefs must say: add every new env var to env-descriptions.json, regenerate, and run forge-verify from the main checkout with --dir before committing. Also: with 4 parallel objectives, gradle.sh's 30-minute lock wait gave up (rc 75), so 3 parallel builds is the practical ceiling on one host.
- 2026-11-21: An engineer hit the 80-turn limit on a 12-task L objective with work uncommitted. Brief engineers to commit at task boundaries so a turn limit or usage limit never strands work, and split large task lists across two engineer runs.
- 2026-11-21: Engineers wrote tests that pass whether or not the code is fixed three times (F-05 x2, F-09). Every regression test written for a review finding must be shown to FAIL on the pre-fix commit. Engineer briefs should say: run the new test against the commit that had the bug and record that it fails.
