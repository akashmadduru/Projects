# Snapshot 4 (2026-11-23)

## Gate-passed, PR text written, NOT pushed (12)
F-01, F-03, F-04 (two parts), F-05, F-06, F-07, F-08, F-09, F-10, F-11 (three parts), F-12, F-13 (two parts).

The Owner approved pushing F-01, F-03, F-04, F-05, F-06, F-07 and F-08. The guard blocks pushes from agents, so the Owner must run them. F-09 to F-13 need their own approval.

## In progress
- **F-15 (B-10, COD payment row):** PR-A engineer at work; base is F-11 b7f5b97.
- **F-14 (per-shipment cancel):** design and tasks are accepted; waits for F-15's head.

## Owner decisions queued
1. **Pushes**, and the merge order of the stacked chains.
2. **F-13 fee policy** (PROVISIONAL Q1 = B).
3. **TD-BE-17:** inactive products are readable anonymously.
4. **Governance:** a pre-commit env-reference check, since this run missed an env var 3 times (F-06, F-07, F-13). Also G-5..G-9.
5. **Deferred:** SMS caps (needs F-06 and F-07 merged), O-02 internal token (touches every service), and the F-14 AC-4 seller follow-up (needs F-13 merged).

## Process observations this period
- **Turn limits:** engineers hit the 80-turn limit about 8 times. A brief rule to commit at every task boundary, plus CEO WIP backups to the scratchpad, kept losses at zero.
- **Session restarts (2):** interrupted agents were resumed through SendMessage.
- **Independent review caught real defects:**
  - a vacuous Java `"\b"` regex test (F-12);
  - outbox batch starvation (F-11 A);
  - an unbounded admin repair amount (F-13 B, security);
  - a COD `ORDER_PLACED` event that could be skipped (F-11 C);
  - an AC proof landing in the wrong split part (F-13 A).
- **CEO verify caught** an env-reference miss (F-13) and one contaminated verify run, from running in a worktree an engineer was still editing.
