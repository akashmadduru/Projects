<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo } from '@/lib/adminApi';
import { verifyExchange } from '@/lib/trustApi';

/**
 * Exchange pickup verification. The contract exposes only the verify action, so
 * the quote id comes from the order detail (deep link ?quote=) or is pasted in.
 */
const route = useRoute();
const form = reactive({
  quoteId: typeof route.query.quote === 'string' ? route.query.quote.slice(0, 64) : '',
  finalValue: '',
});
const acting = ref(false);
const notice = ref('');
const error = ref<{ message: string; correlationId: string | null } | null>(null);

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function submit(accepted: boolean): Promise<void> {
  error.value = null;
  notice.value = '';
  const id = form.quoteId.trim();
  if (!UUID_RE.test(id)) {
    error.value = { message: 'Quote id must be a UUID.', correlationId: null };
    return;
  }
  let value: number | undefined;
  if (accepted) {
    value = Number(form.finalValue);
    if (form.finalValue.trim() === '' || !Number.isFinite(value) || value < 0) {
      error.value = { message: 'Enter the final accepted value (0 or more).', correlationId: null };
      return;
    }
  }
  acting.value = true;
  try {
    await verifyExchange(id, accepted, value);
    notice.value = accepted ? `Exchange accepted at ${value}.` : 'Exchange rejected.';
    form.finalValue = '';
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    acting.value = false;
  }
}
</script>

<template>
  <section class="max-w-xl">
    <h1 class="mb-4 text-2xl font-semibold">Exchange verification</h1>
    <p class="mb-4 text-sm opacity-70">
      After the old device is picked up and inspected, accept it with the final value or reject it.
      A rejection records a shortfall on the order.
    </p>

    <ProblemAlert v-if="error" class="mb-3" :message="error.message" :correlation-id="error.correlationId" />
    <div v-if="notice" class="alert alert-success mb-3" role="status" data-test="exchange-notice">{{ notice }}</div>

    <form class="data-card flex flex-col gap-3" @submit.prevent="submit(true)">
      <div class="form-control">
        <label class="label" for="exchange-quote"><span class="label-text">Quote id</span></label>
        <input id="exchange-quote" v-model="form.quoteId" class="input input-bordered input-sm font-mono" data-test="exchange-quote" />
      </div>
      <div class="form-control">
        <label class="label" for="exchange-value"><span class="label-text">Final value (if accepting)</span></label>
        <input
          id="exchange-value"
          v-model="form.finalValue"
          class="input input-bordered input-sm"
          type="number"
          min="0"
          step="0.01"
          data-test="exchange-value"
        />
      </div>
      <div class="flex justify-end gap-2">
        <button class="btn btn-outline btn-error btn-sm" type="button" :disabled="acting" data-test="exchange-reject" @click="submit(false)">
          Reject
        </button>
        <button class="btn btn-primary btn-sm" type="submit" :disabled="acting" data-test="exchange-accept">
          <span v-if="acting" class="loading loading-spinner loading-xs" aria-hidden="true" />
          Accept
        </button>
      </div>
    </form>
  </section>
</template>
