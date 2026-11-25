# F-20261119-02 Storefront background without images and stars

## Objective (verbatim)
> store-front bg images look odd make it plain or add different grid or patterns in theming sections but not images and starts

## Goal
Replace the storefront's decorative starburst wallpaper and floating star/atom/boomerang motifs with a plain
background, optionally with a CSS-only pattern (grid, dots, ...) chosen in the admin theming screen.

## Affected areas (found by search; excerpts read)
- `apps/product-catalog/src/components/Layout.tsx:22-43` `AtomicBackdrop`: always renders `.bg-atomic-pattern`
  (SVG starburst wallpaper), a large spinning `Atom`, three twinkling `Starburst`s and a drifting `Boomerang`,
  regardless of the theme.
- `apps/product-catalog/src/style.css:80-81, 130-131, 191-196`: `--pattern-image` (inline SVG starbursts and dots)
  and `--pattern-opacity`; `.bg-atomic-pattern`.
- `apps/product-catalog/src/lib/themeEngine.ts` (byte-identical copy in `apps/admin-product-catalog/src/lib/`):
  `patternUri()` (:775) re-tints the starburst wallpaper per theme (:826, :858). `StyleFx.backdrop`
  (`none|aurora|grid|dots|scanlines|spatial|paper`, :85) and `backdropRule()` (:1411) already render CSS-only
  page backdrops, but only as part of a design-style preset (`style.id`), not as their own setting.
- `apps/admin-product-catalog/src/views/ThemeView.vue`: theming screen (design style picker, ChoiceGroups).
- `services/product-service/.../web/ThemeController.java:364-377, 433+`: the saved config is bound to typed
  records and validated field by field against allow-lists, so any NEW theme field is dropped unless added here.

## Related tech debt
None found specific to the storefront backdrop.

## Assumptions
- "starts" means "stars" (the twinkling `Starburst` motifs).
- "images" means the decorative SVG wallpaper and motifs, not product or banner images.
- Patterns must be CSS-only (gradients), no image assets.

## Blocking questions
1. Plain only (storefront-only change), or plain by default plus a selectable background pattern in the
   theming screen (storefront + admin + product-service validation)?
   **Owner answer (2026-11-19):** plain by default plus a selectable, CSS-only background pattern in the
   admin theming screen. Remove the wallpaper and the star/atom/boomerang motifs.
