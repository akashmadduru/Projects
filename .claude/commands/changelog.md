---
description: Generate a Changes.md entry and/or ReleaseNotes.md entry for the current change set.
argument-hint: [optional: version label]
agent: polymath
pipeline: false
---

# /changelog

## Steps

1. Present Model Selection — default Haiku (this is markdown generation from known
   facts).
2. Present Token Budget — default Medium.
3. Delegate to Documentation Writer, scoped specifically to `templates/changes.md` and,
   if `$ARGUMENTS` includes a version label or the change is user-facing,
   `templates/release-notes.md`.
4. Documentation Writer distinguishes internal/technical changes (Changes.md, engineer
   audience) from user-facing highlights (ReleaseNotes.md, plain language).

## Output

`Changes.md` entry (and `ReleaseNotes.md` entry if applicable), plus Standard Output
Contract.
