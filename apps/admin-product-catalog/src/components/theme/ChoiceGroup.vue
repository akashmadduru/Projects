<script setup lang="ts" generic="T extends string">
import type { Choice } from '@/lib/themeEngine';

/**
 * A labelled set of mutually exclusive theme options (toggle buttons with aria-pressed). `swatch`
 * optionally draws a small sample for each value so the choice is visible before it is made.
 */
const props = defineProps<{
  label: string;
  choices: readonly Choice<T>[];
  modelValue: T;
  testPrefix?: string;
  swatch?: (value: T) => Record<string, string> | undefined;
  compact?: boolean;
}>();

/** `preview` fires with a value while an option is hovered/focused, and with null when it is left. */
const emit = defineEmits<{ 'update:modelValue': [value: T]; preview: [value: T | null, label: string] }>();
</script>

<template>
  <div class="choice-group" role="group" :aria-label="props.label">
    <span class="choice-label">{{ props.label }}</span>
    <div class="choice-list" :class="{ 'choice-list-compact': props.compact }">
      <button
        v-for="c in props.choices"
        :key="c.value"
        type="button"
        class="choice"
        :class="{ 'choice-active': props.modelValue === c.value }"
        :aria-pressed="props.modelValue === c.value"
        :title="c.hint"
        :data-test="props.testPrefix ? `${props.testPrefix}-${c.value}` : undefined"
        @click="emit('update:modelValue', c.value)"
        @mouseenter="emit('preview', c.value, `${props.label}: ${c.label}`)"
        @focus="emit('preview', c.value, `${props.label}: ${c.label}`)"
        @mouseleave="emit('preview', null, '')"
        @blur="emit('preview', null, '')"
      >
        <span v-if="props.swatch" class="choice-swatch" :style="props.swatch(c.value)" aria-hidden="true" />
        <span class="choice-text">
          <span class="choice-name">{{ c.label }}</span>
          <span v-if="!props.compact" class="choice-hint">{{ c.hint }}</span>
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.choice-group {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.choice-label {
  font-size: 0.875rem;
}
.choice-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr));
  gap: 0.4rem;
}
.choice-list-compact {
  grid-template-columns: repeat(auto-fill, minmax(7rem, 1fr));
}
.choice {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  text-align: left;
  padding: 0.4rem 0.55rem;
  border: 2px solid var(--color-base-300);
  border-radius: var(--radius-field);
  background: var(--color-base-100);
  transition: border-color 150ms ease;
}
.choice:hover,
.choice:focus-visible {
  border-color: var(--color-primary);
}
.choice-active {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px var(--color-primary);
}
.choice-swatch {
  flex: none;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 6px;
  border: 1px solid var(--color-base-300);
  background: var(--color-base-100);
}
.choice-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.choice-name {
  font-size: 0.875rem;
  font-weight: 500;
}
.choice-hint {
  font-size: 0.72rem;
  opacity: 0.65;
}
</style>
