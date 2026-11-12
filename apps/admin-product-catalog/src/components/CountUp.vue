<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';

/**
 * Animates an integer from its previous value to `value` (ease-out). Skips the animation entirely
 * when the user prefers reduced motion. Renders the final value immediately on the first paint
 * if `value` is already known, so tests and screenshots never catch it mid-count.
 */
const props = withDefaults(defineProps<{ value: number; durationMs?: number }>(), { durationMs: 900 });

const shown = ref(0);
let raf = 0;

function run(to: number): void {
  cancelAnimationFrame(raf);
  const reduce =
    typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  if (reduce || typeof requestAnimationFrame === 'undefined') {
    shown.value = to;
    return;
  }
  const from = shown.value;
  const start = performance.now();
  const tick = (now: number): void => {
    const t = Math.min(1, (now - start) / props.durationMs);
    shown.value = Math.round(from + (to - from) * (1 - Math.pow(1 - t, 3)));
    if (t < 1) raf = requestAnimationFrame(tick);
  };
  raf = requestAnimationFrame(tick);
}

watch(() => props.value, run, { immediate: true });
onBeforeUnmount(() => cancelAnimationFrame(raf));
</script>

<template>
  <span class="tabular-nums">{{ shown.toLocaleString() }}</span>
</template>
