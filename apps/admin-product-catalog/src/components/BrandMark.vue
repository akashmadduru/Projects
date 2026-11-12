<script setup lang="ts">
/**
 * The Cartwise mark: a cart riding an atomic orbit. Same geometry as the storefront's
 * `LogoMark` (apps/product-catalog/src/components/Logo.tsx) and `public/favicon.svg`; keep the
 * three in sync. Colours come from the `--cw-*` tokens in style.css so it follows light/dark.
 *
 * `orbit` is the ring colour: pass `on-brand` when the mark sits on the teal brand gradient, and
 * leave the default (`surface`) when it sits on a light/dark page surface where cream would vanish.
 */
withDefaults(defineProps<{ orbit?: 'on-brand' | 'surface' }>(), { orbit: 'surface' });
</script>

<template>
  <svg viewBox="0 0 40 40" aria-hidden="true" focusable="false">
    <circle cx="20" cy="20" r="17" style="fill: var(--cw-accent); stroke: var(--cw-ink)" stroke-width="2" />
    <ellipse
      cx="20"
      cy="20"
      rx="19"
      ry="7"
      fill="none"
      :style="{ stroke: orbit === 'on-brand' ? 'var(--cw-on-brand)' : 'var(--color-primary)' }"
      stroke-width="2"
      transform="rotate(-25 20 20)"
    />
    <circle cx="37" cy="12" r="2.6" style="fill: var(--cw-amber); stroke: var(--cw-ink)" stroke-width="1.2" />
    <path
      d="M11 13h2.2l2 8.6a1.2 1.2 0 0 0 1.2.9h6.8a1.2 1.2 0 0 0 1.2-.9L26 16H14.3"
      fill="none"
      style="stroke: var(--cw-ink)"
      stroke-width="2.2"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <circle cx="17" cy="26.5" r="1.5" style="fill: var(--cw-ink)" />
    <circle cx="23" cy="26.5" r="1.5" style="fill: var(--cw-ink)" />
  </svg>
</template>
