<script setup lang="ts">
import { computed } from 'vue';

import BrandMark from '@/components/BrandMark.vue';
import { useBranding } from '@/composables/useBranding';

/**
 * "Cartwise Admin" lockup. Two placements:
 *  - `on-brand`  : on the teal gradient (sidebar top strip). Cream text with the storefront's ink
 *                  text-shadow. `collapsed` shows only the mark.
 *  - `surface`   : on the page/card surface (login, register). Theme text colour, larger.
 */
withDefaults(
  defineProps<{
    tone?: 'on-brand' | 'surface';
    collapsed?: boolean;
    /** Show the "Admin" pill after the name. */
    tag?: boolean;
    /** Larger mark and type, for the login hero. */
    large?: boolean;
  }>(),
  { tone: 'on-brand', collapsed: false, tag: true, large: false },
);

const { name, logoUrl } = useBranding();

/** Multi-word names accent the last word; a single word stays one colour. */
const parts = computed(() => {
  const words = name.value.trim().split(/\s+/);
  return words.length > 1
    ? { head: words.slice(0, -1).join(' ') + ' ', tail: words[words.length - 1] }
    : { head: name.value, tail: '' };
});
const accentLetters = computed(() => name.value === 'Cartwise');
</script>

<template>
  <span
    class="brand inline-flex items-center gap-2"
    :class="[tone === 'on-brand' ? 'brand-on-brand' : 'brand-surface']"
    role="img"
    :aria-label="`${name} Admin`"
  >
    <img
      v-if="logoUrl"
      :src="logoUrl"
      alt=""
      class="brand-mark shrink-0 object-contain"
      :class="large ? 'h-16 w-16' : 'h-9 w-9'"
    />
    <BrandMark
      v-else
      :orbit="tone"
      class="brand-mark shrink-0"
      :class="large ? 'h-16 w-16' : 'h-9 w-9'"
    />
    <template v-if="!collapsed">
      <span class="font-display leading-none tracking-wide" :class="large ? 'text-4xl' : 'text-xl'">
        <template v-if="accentLetters">Cart<span class="brand-accent">wise</span></template>
        <template v-else>{{ parts.head }}<span class="brand-accent">{{ parts.tail }}</span></template>
      </span>
      <span v-if="tag" class="brand-tag" :class="large ? 'text-xs' : 'text-[10px]'">Admin</span>
    </template>
  </span>
</template>

<style scoped>
.brand-mark {
  filter: drop-shadow(2px 2px 0 var(--cw-ink));
  transition: transform 800ms var(--ease-spring, ease);
}

.brand:hover .brand-mark {
  transform: rotate(-12deg) scale(1.08);
}

.brand-on-brand {
  color: var(--cw-on-brand);
}

.brand-on-brand .font-display {
  text-shadow: 2px 2px 0 var(--cw-ink);
}

.brand-on-brand .brand-accent {
  color: var(--cw-accent);
}

.brand-surface {
  color: var(--color-base-content);
}

.brand-surface .brand-accent {
  color: var(--cw-accent-2);
}

/* "Admin" pill: ink outline + mustard fill, like the storefront's promo flashes. */
.brand-tag {
  border: 2px solid var(--cw-ink);
  border-radius: 9999px;
  background: var(--cw-amber);
  color: var(--cw-ink);
  padding: 0.1rem 0.5rem;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  line-height: 1.3;
}
</style>
