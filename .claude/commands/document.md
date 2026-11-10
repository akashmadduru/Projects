---
description: Generate or update project documentation (Feature.md, Architecture.md, Changes.md, Migration.md, DecisionLog.md, FutureWork.md) for a completed or in-progress change.
argument-hint: <feature/change to document>
agent: polymath
pipeline: false
---

# /document

## Steps

1. Present Model Selection — default Haiku for updates to existing structured docs;
   recommend Sonnet when synthesizing a new `Architecture.md` or `DecisionLog.md` entry
   from scratch across multiple source files.
2. Present Token Budget — default Medium; High when documenting a system-wide change.
3. Delegate to Documentation Writer (`agents/documentation/documentation-writer.md`),
   which determines which of Feature.md / Architecture.md / Changes.md / Migration.md /
   ReleaseNotes.md / DecisionLog.md / FutureWork.md apply to `$ARGUMENTS`.
4. Documentation Writer pulls facts only from the actual implementation/plan already in
   context — if no implementation exists yet for what's being documented, it states that
   explicitly rather than fabricating detail.

## Output

The applicable documentation files, written/updated, plus Standard Output Contract
listing each document's path and purpose.
