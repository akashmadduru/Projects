---
name: forge-discover
description: OpenForge step 1. Create the work folder, worktree and branch for an objective and write objective.md with context and blocking questions.
argument-hint: <objective>
---
# Step 1: Discover (CEO)
1. Id: `F-<YYYYMMDD>-<NN>` (next free NN in `.openforge/work/`). Slug: 2–4 kebab words.
2. Base: the current HEAD commit unless the Owner names another. Create the isolated workspace:
   `git worktree add .openforge/worktrees/<id> -b forge/<id>-<slug> <base>`
3. Write `.openforge/work/<id>/objective.md`: the objective verbatim, goal in one sentence, affected
   areas (paths found with Grep/Glob; read excerpts only), related tech-debt items, assumptions, and
   blocking questions. Every assumption about runtime behaviour (proxies, headers, framework
   defaults, deployment config) must cite its proof: a file and line, or a library source/jar entry.
   An assumption with no proof is a blocking question or a design spike, never a fact.
4. Write `state.json`: `{"id","slug","step":"discover","branch","worktree","base_sha","implementer":null,"head_sha":null}` and the first `log.md` line.
5. If there are blocking questions, ask the Owner now and record the answers in objective.md.
Gate: objective.md exists and has no unanswered blocking question.
