---
description: Research and produce a concrete visual design direction (layout pattern, palette, typography, assets) for a UI, sourced against real inspiration/asset references. Generative, not implementation.
argument-hint: <what needs a design direction — e.g. "software-engineer portfolio site", "admin dashboard redesign">
agent: ui-ux-designer
pipeline: true
---

# /design-direction

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet when generating a full
   visual identity for a new product/portfolio/landing page, or a whole-app redesign
   rather than one page/component.
2. Present Token Budget — default Medium; High when cross-referencing many inspiration
   sources for an original identity, or when the codebase already has an established
   design system that must be read in full before proposing changes.
3. UI/UX Designer clarifies the brief (domain, audience, brand constraints) if not
   already clear from `$ARGUMENTS`.
4. UI/UX Designer researches against Dribbble, Behance, Awwwards (visual inspiration),
   21st.dev (component patterns), and Pixabay/Pexels/OpenArt (imagery), per its Workflow.
5. UI/UX Designer produces the design-direction document (layout, palette, typography,
   effects/interactions, asset plan, anti-patterns, accessibility checklist).
6. Present the direction to the user. Do NOT proceed to implementation without
   confirmation, unless the user pre-authorized a single pass — this command is
   generative/advisory, matching `pipeline: true` commands' approval-gate convention.
7. Once approved, hand off implementation: Tailwind Specialist (`agents/frontend/
   tailwind-specialist.md`) for the design-token system, then Vue/TypeScript Specialist
   (`agents/frontend/vue-typescript-specialist.md`) for components — do not implement
   directly in UI/UX Designer.

## Output

A design-direction document (per `agents/frontend/ui-ux-designer.md`'s Outputs section)
plus Standard Output Contract. Implementation (if requested and approved) is a separate,
subsequent stage handled by the specialists named above.
