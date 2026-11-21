# Run R-20261119-01: OpenForge reliability evaluation on the e-commerce monorepo

## Owner mandate (2026-11-19)
"Automate the entire SDLC with zero or minimal human intervention … Target 90% project completion before
requesting my review … Don't optimize for appearing successful. Prioritize measurable results, transparency,
and genuine engineering quality. Treat failures as opportunities to identify and improve OpenForge's weaknesses."

## Owner answers (AskUserQuestion, this conversation)
| Question | Answer |
|---|---|
| Project | This monorepo: B-1 + the tech-debt register, grouped into objectives; Owner-only items excluded |
| Standing approvals | Class-L designs accepted by the CTO count as Owner-approved; size limit counts production lines only. Every push, merge and governance edit still needs the Owner |
| Rule edits | Apply G-1..G-4 + a metrics recorder (done at run start, see log) |
| Budget | Checkpoint every 3M agent tokens: write a benchmark snapshot and continue unless a stop rule fires |

## Scope and the 90% definition
- In scope: F-20261119-01 (pilot), F-20261119-03 (B-1), and every open item in `docs/techdebt/README.md`
  except the Owner-only items (TD-SEC-01, TD-SEC-14, TD-P6-02, TD-P9-09 providers, TD-SEC-04) and items whose
  fix needs an external account, a vendor or production access (listed as "excluded: external" with reason).
- Unit of completion: an objective is **done** when its PR gate passes (forge-gate exit 0: verify recorded,
  both independent approvals on the head SHA, all ACs evidenced). Nothing is pushed or merged.
- Completion % = in-scope register items closed by done objectives / in-scope items. 90% triggers the stop and
  the Owner review package.

## Stop rules (any one pauses the run for the Owner)
1. An objective fails review 3 rounds in a row (constitution).
2. Rolling done-rate below 70% over the last 5 objectives.
3. A destructive or irreversible action would be needed (data migration that drops data, history rewrite).
4. A finding of severity blocker that the fix loop cannot resolve.

## Measurement (honesty rules)
- Tokens, tool calls and time come only from harness completion notices, recorded with `metrics.mjs add`.
- Correctness comes from verify output and independent verdicts, never from an implementer's report.
- Every Owner touch is logged as an `intervention`; every CEO repair of agent output as a `recovery`.
- Defect detection is measured with seeded defects: at least one objective's review gets a copy of the diff
  with known injected bugs; detection rate = caught / injected. Reported even if poor.
- A final independent audit (fresh agents, not used in the run) samples done objectives for escaped defects.
- Main-session (CEO) tokens are not reported by the harness: stated as a known blind spot.
