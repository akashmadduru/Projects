<script setup lang="ts">
/**
 * Renders a server-supplied failure message.
 *
 * `role="alert"` (not just colour) is what makes the failure reach a screen reader
 * without the user having to go looking for it.
 */
defineProps<{
  message: string;
  /** Shown only when present; it is the handle support needs to find the log line. */
  correlationId?: string | null;
}>();
</script>

<template>
  <div
    class="alert alert-error"
    role="alert"
    data-test="problem-alert"
  >
    <div class="flex flex-col items-start gap-1 text-left">
      <span>{{ message }}</span>
      <span v-if="correlationId" class="text-xs opacity-70">
        Reference: {{ correlationId }}
      </span>
    </div>
  </div>
</template>
