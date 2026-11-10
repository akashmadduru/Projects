---
description: Review Rust code — ownership/lifetime design, unsafe block justification, concurrency model, allocation discipline, benchmark evidence. Read-only.
argument-hint: <target crate/module>
agent: rust-systems-architect
pipeline: false
---

# /rust-review

## Steps

1. Present Model Selection — recommend Sonnet by default (ownership/concurrency/unsafe
   review benefits from deeper analysis).
2. Present Token Budget — default Medium; High when reviewing a crate with extensive
   `unsafe` usage or a complex concurrency model.
3. Delegate to Rust Systems Architect (`agents/backend/rust-systems-architect.md`) in
   review-only mode, scoped to `$ARGUMENTS`.
4. The specialist checks ownership/borrowing design, unjustified `.clone()` usage,
   `unsafe` block documentation and scope, concurrency model fit, `clippy`/`fmt`
   cleanliness, and whether performance claims are backed by `criterion` benchmarks,
   per its Workflow.

## Output

`templates/review-report.md` plus Standard Output Contract.
