# Phase 1: Architecture and Design Decisions

**Phase:** 1 (Micro-interactions)  
**Decided:** November 2026  
**Status:** Implemented

## Decision 1: CSS-Only Animations (No External Library)

**Decision:** Implement all animations using native CSS `@keyframes` and `requestAnimationFrame` instead of Framer Motion, React Spring, or similar libraries.

**Rationale:**

1. **Bundle Size:** Adding Framer Motion (+35 KB gzipped) would increase total bundle by 4-5x the value delivered in Phase 1. CSS animations scale the impact across all future interactions without additional payload.

2. **Bundle Ownership:** Every engineer can understand CSS animations; complex easing functions in motion libraries require library-specific knowledge that creates onboarding friction.

3. **Browser Native Performance:** CSS animations are GPU-accelerated by default (when using `transform` and `opacity`). Framer Motion, while performant, adds a JavaScript execution layer that can cause jank on lower-end devices.

4. **Framework Agnostic:** If the team ever migrates from React, CSS animations are portable; JavaScript libraries are not.

5. **Simplicity:** The animation requirements (ripple, star fill, counter, stagger) are straightforward enough that custom logic is less complex than learning library APIs.

**Alternatives Considered:**

- **Framer Motion:** Rejected due to bundle size and complexity overhead for simple animations
- **React Spring:** Similar size/complexity concerns; would require component refactoring
- **Plain CSS:** Used for 3 of 4 animations; works well (ripple, star fill, stagger)
- **Animate.css:** Pre-built animations; rejected because we need custom timings and control

**Trade-offs:**

- **Pro:** Smaller bundle, more control, easier debugging
- **Con:** Custom easing functions require manual implementation; can be error-prone if timings change frequently

**Consequence:** Phase 2+ animations will still use CSS + requestAnimationFrame pattern.

---

## Decision 2: Ripple Effect via requestAnimationFrame + DOM Manipulation

**Decision:** Implement ripple effect by creating temporary DOM elements on click, rather than using pure CSS animations or canvas.

**Rationale:**

1. **Position Tracking:** Ripple must expand from click point, which is dynamic per interaction. Pure CSS animations cannot respond to click coordinates without inline styles or JavaScript.

2. **Cleanup Simplicity:** DOM elements are garbage-collected immediately after removal; canvas-based approach would require manual cleanup and memory management.

3. **Composability:** Ripple renders on top of existing card content naturally via DOM hierarchy; canvas would require complex layer management.

4. **Accessibility:** DOM elements can carry ARIA attributes if future requirements demand; canvas cannot.

5. **Performance:** Single DOM node per click is negligible overhead; ripple removal at 300ms prevents memory accumulation.

**Alternatives Considered:**

- **Canvas API:** Would give better performance for multiple simultaneous ripples but adds complexity and removes accessibility options
- **Pure CSS:** Cannot respond to click position without JavaScript-generated inline styles (we use those anyway)
- **SVG:** More overhead than `<div>` for a circular element; no benefit over DOM

**Trade-offs:**

- **Pro:** Simple, readable code; scales to multiple ripples naturally
- **Con:** DOM mutation on every click; imperatively styled; harder to test click position math

**Consequence:** Ripple effect is non-configurable per-instance without component prop changes. Centralize timing if global adjustment needed.

---

## Decision 3: StarRating Uses Individual SVG Elements

**Decision:** Render each star as a separate `<svg>` element with individual animation delays, rather than using icon fonts, CSS borders, or a single SVG.

**Rationale:**

1. **Stagger Animation:** Per-star animation delays require per-star elements. A single SVG with multiple `<path>` children cannot receive different animation delays on child elements (CSS animations target the entire SVG).

2. **Fill Opacity Control:** Each star needs independent `fill-opacity` animation. SVG `<path>` elements allow this naturally; icon fonts do not support fill animation.

3. **Accessibility:** SVG in HTML can include title/desc elements and ARIA attributes; CSS-generated icons cannot.

4. **Rendering:** SVG renders at viewport resolution without aliasing artifacts; CSS borders or emoji can appear blocky.

5. **Semantic Clarity:** Each star is a distinct visual element in the DOM, matching user perception.

**Alternatives Considered:**

- **Single SVG with masked paths:** Would require CSS mask animations; harder to control per-star timing
- **Icon Font (Font Awesome):** Cannot animate individual glyphs; adds font file payload
- **Emoji:** Rendering inconsistent across platforms; no animation support
- **CSS Borders:** Difficult to achieve star shape precisely; cannot animate fill

**Trade-offs:**

- **Pro:** Full control over timing and appearance; semantically correct
- **Con:** Renders 5 SVG elements per component (negligible for small star ratings); requires `animationDelay` inline style

**Consequence:** If stars become configurable count in future (e.g., 10-star or 3-star ratings), stagger math becomes `index * delayUnit` where `delayUnit` adjusts based on count.

---

## Decision 4: PriceDisplay Uses requestAnimationFrame for Counter Animation

**Decision:** Implement price counter as a JavaScript animation using `requestAnimationFrame` and custom easing, rather than CSS animation or third-party tween library.

**Rationale:**

1. **Numeric Precision:** CSS animations cannot display intermediate numeric values; `counter-increment` CSS property doesn't support decimal precision needed for prices.

2. **Localization:** Price formatting (currency symbols, decimal places, comma separators) must happen in JavaScript via `Intl.NumberFormat`; CSS has no locale-aware formatting.

3. **Smooth 60fps:** `requestAnimationFrame` guarantees callback every ~16ms, matching screen refresh rate. Manual `setInterval` could miss frames or cause stuttering.

4. **Easing Control:** Custom cubic easeOut function `1 - (1 - progress)^3` is simpler in JavaScript than CSS cubic-bezier values. We needed "quick start, slow finish" easing that's transparent to readers.

5. **prefers-reduced-motion Support:** Runtime detection of user preference is trivial; CSS media queries apply globally. We can skip animation entirely if user preference set.

6. **Memoization Path:** Future optimization to skip animation if price unchanged requires React state; easier to extend in JavaScript than CSS.

**Alternatives Considered:**

- **CSS @keyframes:** Cannot format numbers or respond to locale
- **setTimeout loop:** Less precise than requestAnimationFrame; no frame synchronization guarantee
- **Framer Motion:** Overkill for single numeric animation
- **No animation (instant update):** Reduces perceived product polish; loses visual feedback cue

**Trade-offs:**

- **Pro:** Precise numeric formatting, accessibility-first, extensible
- **Con:** Adds ~30 lines of JavaScript; requires frame cleanup on unmount

**Consequence:** Price changes trigger animation every time; future optimization should track previous value and skip redundant animations.

---

## Decision 5: Grid Stagger Using Per-Card Inline Styles

**Decision:** Implement grid entrance stagger with `animation` property in inline `style` attribute per product, rather than CSS class-based approach or JavaScript timeline.

**Rationale:**

1. **Per-Instance Timing:** Each card needs unique delay based on its index in the array. CSS classes cannot vary per-instance without generating N classes.

2. **Maintainability:** Calculation `${index * 0.05}s` is clear and single-source-of-truth in one place; generating CSS classes moves logic into CSS or a build step.

3. **React Idiomatic:** Using `style` prop is standard React pattern for dynamic styles; separating into CSS requires tooling or CSS-in-JS library.

4. **GPU Optimization:** `will-change-transform` CSS hint ensures browser pre-allocates GPU layers; inline style coexists with Tailwind utilities naturally.

5. **No Layout Shift:** Using `transform: translateY()` in animation prevents reflow. Verified with Lighthouse; no CLS impact.

**Alternatives Considered:**

- **CSS Classes Generated Dynamically:** Would require Tailwind config with 12+ stagger delay classes; not idiomatic
- **JavaScript Timeline (gsap/Framer Motion):** Would add library dependency we rejected in Decision 1
- **CSS Grid Animation:** Native grid animations are limited; cannot sequence individual grid items
- **Skeleton Loading:** Show placeholder grid while loading; stagger real items on mount (separate feature)

**Trade-offs:**

- **Pro:** Simple, maintainable, React idiomatic
- **Con:** Inline styles are less cacheable than CSS classes; if grid size changes dynamically, calculations must account for that

**Consequence:** Stagger timing is proportional to index; if products are reordered (via sort/filter), stagger sequence becomes visually confusing. Document this as known limitation.

---

## Decision 6: Accessibility-First Implementation

**Decision:** Ensure all animations respect `prefers-reduced-motion` system preference and include ARIA labels/semantic HTML for screen readers.

**Rationale:**

1. **Inclusivity:** 1 in 4 users have a disability; vestibular disorders cause motion sensitivity in ~35% of those. Respecting motion preference is essential, not optional.

2. **Legal Compliance:** WCAG 2.1 Level AA requires respecting animation preferences (Success Criterion 2.3.3 for flashing; 2.3.5 for motion).

3. **Device Performance:** Disabling animations on low-end devices improves perceived responsiveness; many devices report `prefers-reduced-motion` when battery is low.

4. **Testing Simplicity:** Disabling animations makes tests deterministic; QA can verify interactions without waiting for animation completion.

5. **User Control:** Respecting preferences signals we trust users to know what's best for their devices and bodies.

**Alternatives Considered:**

- **No prefers-reduced-motion support:** Rejected; accessibility is non-negotiable
- **Optional `enableAnimations` prop:** Rejected; user preference should be default, not opt-in
- **Per-animation preference:** Rejected; too complex; global reduction is sufficient for Phase 1

**Trade-offs:**

- **Pro:** Inclusive, compliant, future-proof
- **Con:** Requires testing in reduced-motion mode; adds ~5 lines per component

**Consequence:** If Phase 2 adds animations, prefers-reduced-motion support is mandatory.

---

## Decision 7: Animation Timing Values (Hardcoded Constants vs Config)

**Decision:** Hardcode animation timings (0.3s, 0.4s, 0.6s, 0.8s, stagger delays) in components and CSS, rather than centralizing in a config object.

**Rationale:**

1. **Early Stage:** Phase 1 is exploratory; centralizing before we understand final UX is premature optimization.

2. **Local Clarity:** Readers of ProductCard.tsx immediately see ripple duration (0.3s) in context; searching for a config value is context-switching.

3. **Low Change Frequency:** Once animations are tuned to product spec, timings rarely change. Not centralizing doesn't block future changes.

4. **Minimal Duplication:** Only 4–5 unique timing values across the codebase; centralization overhead is not justified.

**Alternatives Considered:**

- **Centralized `animationConfig.ts`:** Would require importing/updating in 5 files; adds indirection
- **CSS Custom Properties (`--animation-ripple-duration`):** Would work but adds CSS file complexity for values rarely changed
- **Tailwind Config:** Requires understanding Tailwind's animation system; not a good fit for JavaScript-controlled timings

**Trade-offs:**

- **Pro:** Simpler code, less indirection, easier debugging
- **Con:** Changing global animation feel requires editing 5 files; potential for inconsistency

**Consequence:** If Phase 2+ requires animation config, centralize at that time. Document this decision in decision log so future engineers know why it wasn't done initially.

---

## Decision 8: No Performance Metrics Collection (Phase 1)

**Decision:** Do not add Web Vitals tracking or animation performance monitoring to Phase 1.

**Rationale:**

1. **Premature Optimization:** We have no performance baseline or regression risk yet; instrumenting now is speculative.

2. **Bundle Cost:** Adding Web Vitals library is unnecessary overhead for animations that are GPU-accelerated and sub-millisecond CPU cost.

3. **Later Measurement:** Once we have user feedback, we can measure specific concerns (e.g., if mobile users report jank, we'll profile then).

4. **Simplicity:** Phase 1 is validation; instrumenting complicates the feature and adds noise to analytics.

**Alternatives Considered:**

- **Add performance hooks:** Rejected; adds complexity without immediate benefit
- **Browser DevTools profiling:** Sufficient for developer testing; users report issues if needed

**Trade-offs:**

- **Pro:** Minimal additional code; simpler feature
- **Con:** Cannot detect regressions until users report them

**Consequence:** If Phase 2 shows animation jank, add performance monitoring then.

---

## Decision 9: Star Animation Always Applies (No Disable Per-Instance)

**Decision:** All StarRating components animate stars on mount; no prop to disable animation per-instance.

**Rationale:**

1. **Consistent UX:** Animations should always execute for visual coherence. Disabling selectively would create inconsistent behavior.

2. **No Use Case Yet:** No requirement identified for disabling animations on specific star instances; adding this capability now is over-engineering.

3. **Simplicity:** Zero props means fewer test cases and no API confusion.

4. **Global Control:** Users can disable all animations via `prefers-reduced-motion`; developers can disable site-wide animations via CSS if needed.

**Alternatives Considered:**

- **Add `disableAnimation` prop:** Adds API surface without evidence of need
- **Config-driven:** Would require passing config through component tree; overkill for Phase 1

**Trade-offs:**

- **Pro:** Simpler component, consistent UX
- **Con:** Cannot customize animation per-instance without changing code

**Consequence:** If future use cases require per-instance animation control, add as optional prop in Phase 2+.

---

## Decision 10: Ripple Duration Override (0.3s vs CSS 0.6s)

**Decision:** ProductCard intentionally overrides CSS ripple duration from 0.6s to 0.3s via inline style.

**Rationale:**

1. **User Feedback Speed:** 0.3s feels more responsive to click actions; 0.6s feels laggy when combined with page navigation.

2. **Material Design Precedent:** Google Material Design uses 0.4s ripple duration; our 0.3s is even faster.

3. **Intentional Variation:** Different animation timings per context (ripple vs star vs grid) create visual hierarchy and prevent monotony.

4. **Override Strategy:** Using inline style allows CSS default (0.6s) to apply in other contexts while ProductCard gets faster feedback.

**Alternatives Considered:**

- **Use 0.6s universally:** Feels sluggish for click feedback
- **Use 0.3s in CSS:** Would slow down other ripple use cases (if any)
- **Make configurable:** Not needed; hardcoded value is intentional tuning

**Trade-offs:**

- **Pro:** Better UX; CSS value remains useful for future ripple uses
- **Con:** Inconsistency between CSS definition and actual usage (documented in Phase1-Feature.md)

**Consequence:** Document this as intentional in code comments; future developers won't assume it's a bug.

---

## Summary of Key Principles

1. **Accessibility First:** All animations respect user preferences
2. **Bundle Conscious:** No external libraries; CSS + requestAnimationFrame only
3. **Simple and Local:** Hardcoded values until centralization is justified
4. **GPU-Accelerated:** Use `transform` and `opacity` for smooth 60fps animations
5. **Browser Native:** Leverage CSS animations and Web APIs, not polyfills

## Future Decisions

These will be revisited in Phase 2+ planning:

- Centralize animation timing config if Phase 2 adds >10 animations
- Add animation preference hooks if multiple components need coordinated timing
- Implement loading state animations (skeleton, shimmer)
- Add touch feedback (haptic) for mobile devices
- Performance monitoring if user feedback indicates jank

---

## References

- Implementation details: `Phase1-Feature.md`
- Technical changes: `Phase1-Changes.md`
- Known issues: `Phase1-Feature.md` (Known Issues section)
