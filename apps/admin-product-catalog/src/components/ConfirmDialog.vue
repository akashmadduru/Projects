<script setup lang="ts">
/**
 * Generic confirmation dialog — not product-specific, so anything in the app
 * needing a "are you sure" step (destructive or not) can reuse it instead of
 * hand-rolling another modal.
 */
withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    message: string;
    confirmLabel?: string;
    cancelLabel?: string;
    /** Renders the confirm button as `btn-error`; set false for non-destructive confirmations. */
    destructive?: boolean;
    submitting?: boolean;
  }>(),
  {
    confirmLabel: 'Confirm',
    cancelLabel: 'Cancel',
    destructive: true,
    submitting: false,
  },
);

const emit = defineEmits<{
  confirm: [];
  cancel: [];
}>();
</script>

<template>
  <div v-if="open" class="modal modal-open" role="dialog" aria-modal="true" data-test="confirm-dialog">
    <div class="modal-box data-card-elevated" :aria-label="title">
      <h3 class="text-lg font-semibold">{{ title }}</h3>
      <p class="mt-2 opacity-80">{{ message }}</p>

      <div class="modal-action">
        <button
          class="btn btn-ghost"
          type="button"
          data-test="confirm-dialog-cancel"
          :disabled="submitting"
          @click="emit('cancel')"
        >
          {{ cancelLabel }}
        </button>
        <button
          class="btn"
          :class="destructive ? 'btn-error' : 'btn-primary'"
          type="button"
          data-test="confirm-dialog-confirm"
          :disabled="submitting"
          @click="emit('confirm')"
        >
          <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
          {{ confirmLabel }}
        </button>
      </div>
    </div>
    <button
      class="modal-backdrop"
      type="button"
      aria-label="Close dialog"
      data-test="confirm-dialog-backdrop"
      @click="emit('cancel')"
    />
  </div>
</template>
