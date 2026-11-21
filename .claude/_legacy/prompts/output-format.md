# Shared Prompt Fragment: Standard Output Contract

> Every command, and every agent producing a final (non-intermediate) deliverable, MUST close its response with this structure. Sections with nothing to report MUST still appear, with "None identified" rather than being omitted — omission is treated as a validation failure, not brevity.

```markdown
## Summary
One paragraph: what was requested, what was done, current state.

## Files Changed
| File | Change Type (Add/Modify/Delete) | Reason |
|------|----------------------------------|--------|

## Reasoning
Why this approach was chosen over alternatives. Reference constraints, existing patterns, and tradeoffs.

## Architecture Decisions
Any decisions with structural consequence (new dependency, new layer, new pattern introduced). Format as: Decision → Alternatives considered → Why chosen.

## Risks
Concrete risks introduced or remaining, ranked by severity (High/Medium/Low).

## Technical Debt
Debt introduced by this change, and pre-existing debt encountered but not addressed (with justification for deferring it).

## Future Improvements
Specific, actionable follow-ups. Not vague ("improve performance") — concrete ("memoize `useFilteredOrders` selector; O(n²) filter on every render with n > 500").

## Performance Notes
Any Big-O, bundle-size, render-count, query-count, or latency implications.

## Security Notes
AuthN/AuthZ implications, input validation, secrets handling, dependency vulnerabilities touched.

## Testing
What tests were added/updated, what remains uncovered, and why.

## Documentation
Which docs were generated/updated (link/path), and which are still needed.

## Checklist
- [ ] Architecture preserved
- [ ] No unnecessary rewrites
- [ ] TypeScript strictness maintained (frontend work)
- [ ] Composition API used (frontend work)
- [ ] SOLID / Clean Architecture respected (backend work)
- [ ] Tests added or updated
- [ ] Docs updated
- [ ] Security reviewed
- [ ] Performance reviewed
- [ ] No regressions identified
```

## Rules

- Never delete a section. If a section is not applicable, write "N/A — <one line reason>".
- Keep prose in each section tight — this is a report, not a narrative. Bullets are appropriate here (this is a structured deliverable, not conversational output).
- The Polymath orchestrator is responsible for merging per-agent outputs into one top-level report using this exact structure before presenting to the user.
