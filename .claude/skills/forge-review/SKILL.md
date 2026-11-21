---
name: forge-review
description: OpenForge step 8. Independent review of the exact head commit by the Reviewer and Security agents, stored with the SHA; loops back on request_changes.
---
# Step 8: Independent review
1. Write the change under review: `git -C <worktree> diff <base_sha>..<head_sha> > .openforge/work/<id>/review-input.diff`.
2. Delegate to `forge-reviewer` and `forge-security` (in parallel is fine: both are read-only) with
   the work folder path and the SHA. They must not be the implementer (they are different agents with
   no write tools; the hook checks the names too).
3. Save each JSON reply verbatim to `review.json` / `security.json`. If a reply is not valid JSON,
   ask once for the JSON only.
4. Any `request_changes`: send the findings to the implementer (step 6), then re-run QA (step 7) and
   this step on the new SHA. Stop after 3 rounds and ask the Owner.
5. Re-review rounds are delta-first: also write `review-delta.diff` (`<last_reviewed_sha>..<head_sha>`),
   archive the previous verdicts in `history/`, and give reviewers the delta and those verdicts; the full
   diff is reference only. The verdict still names the full head SHA.
Gate: both verdicts `approve` for the current head_sha.
