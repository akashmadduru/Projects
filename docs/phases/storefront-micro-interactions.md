# Storefront micro-interactions (UI track)

**Status:** Built 2026-11-08 to 11-10 in `apps/product-catalog`. Re-checked against the code on 2026-11-14.

This work was first documented as "Phase 1", which clashed with the backend's Phase 1. It is a
storefront UI track, not a platform phase. The original four documents (Feature, Changes,
DecisionLog, Technical-Debt) and the test plan were merged into this page.

## Current animations

| Interaction | Component | Timing (from the code) | Notes |
|---|---|---|---|
| Star fill | `StarRating.tsx` | `starFill` 700 ms, `--ease-smooth`, 100 ms stagger per star | Always on mount; no per-instance opt-out |
| Price counter | `PriceDisplay.tsx` | 800 ms `requestAnimationFrame`, ease-out | Skips the animation under `prefers-reduced-motion`; cancels the frame on unmount |
| Grid entrance | `ProductGrid.tsx` | `slideUpStagger` 0.9 s, `cubic-bezier(0.22,1,0.36,1)`, 70 ms per card | Inline per-card `animation` |
| ~~Ripple on card click~~ | `ProductCard.tsx` | — | **Removed** in commit `5943ec7`. The old docs still described it. |

A global `@media (prefers-reduced-motion: reduce)` rule (`src/style.css`) freezes animations for users
who ask for reduced motion.

## Decisions that still apply

1. **No animation library.** Only CSS `@keyframes` and `requestAnimationFrame`, to keep the bundle small.
2. **Animate `transform` and `opacity` only**, so animations stay on the compositor and cause no layout shift.
3. **Reduced motion first.** Every animation respects the system preference.
4. **Timings live in components and CSS.** Move them to shared tokens once there are many more animations.

## Open issues

- `PriceDisplay` builds a new `Intl.NumberFormat` on every render, twice (value and `aria-label`).
  Cache the formatter.
- The price counter replays on every mount, including when navigating back to a page.
- The stagger delay grows with the index, so card 40 of a large page waits 2.8 s. Cap the delay.
- No per-instance way to turn off star animation.
- Tests: `components/__tests__/MicroInteractions.test.tsx` is among the failing storefront tests
  ([CI/CD baseline](../operations/ci-cd.md#baseline-to-clear-before-ci_enforcetrue)).
