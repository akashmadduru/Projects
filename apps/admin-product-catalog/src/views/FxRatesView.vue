<script setup lang="ts">
import { onMounted, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo } from '@/lib/adminApi';
import { getFxRates, saveFxRates } from '@/lib/trustApi';

/** Display-only FX rates (base USD) used by the storefront currency switcher. */
type ErrorInfo = { message: string; correlationId: string | null };

const base = ref('USD');
const rows = ref<{ code: string; rate: string }[]>([]);
const asOf = ref<string | null>(null);
const loading = ref(false);
const saving = ref(false);
const error = ref<ErrorInfo | null>(null);
const notice = ref('');

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const res = await getFxRates();
    base.value = res.base;
    asOf.value = res.asOf ?? null;
    rows.value = Object.entries(res.rates).map(([code, rate]) => ({ code, rate: String(rate) }));
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    loading.value = false;
  }
}

function addRow(): void {
  rows.value = [...rows.value, { code: '', rate: '' }];
}

function removeRow(index: number): void {
  rows.value = rows.value.filter((_, i) => i !== index);
}

async function save(): Promise<void> {
  error.value = null;
  notice.value = '';
  const rates: Record<string, number> = {};
  for (const r of rows.value) {
    const code = r.code.trim().toUpperCase();
    const rate = Number(r.rate);
    if (!/^[A-Z]{3}$/.test(code) || !Number.isFinite(rate) || rate <= 0) {
      error.value = { message: `Invalid row "${r.code}": need a 3-letter code and a positive rate.`, correlationId: null };
      return;
    }
    rates[code] = rate;
  }
  saving.value = true;
  try {
    const res = await saveFxRates({ base: base.value, rates });
    asOf.value = res?.asOf ?? null;
    notice.value = 'FX rates saved.';
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="max-w-xl">
    <h1 class="mb-1 text-2xl font-semibold">FX rates</h1>
    <p class="mb-4 text-sm opacity-70">
      1 {{ base }} = rate × currency. Display only; checkout always charges in the product currency.
      <span v-if="asOf">As of {{ asOf }}.</span>
    </p>

    <ProblemAlert v-if="error" class="mb-3" :message="error.message" :correlation-id="error.correlationId" />
    <div v-if="notice" class="alert alert-success mb-3" role="status">{{ notice }}</div>

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <form v-else class="data-card flex flex-col gap-2" @submit.prevent="save">
      <div v-for="(r, i) in rows" :key="i" class="flex items-center gap-2" data-test="fx-row">
        <input v-model="r.code" class="input input-bordered input-sm w-24 uppercase" maxlength="3" aria-label="Currency code" />
        <input v-model="r.rate" class="input input-bordered input-sm flex-1" type="number" min="0" step="0.0001" aria-label="Rate" />
        <button class="btn btn-ghost btn-xs" type="button" aria-label="Remove row" @click="removeRow(i)">✕</button>
      </div>
      <div class="flex justify-between pt-2">
        <button class="btn btn-ghost btn-sm" type="button" @click="addRow">Add currency</button>
        <button class="btn btn-primary btn-sm" type="submit" :disabled="saving">
          <span v-if="saving" class="loading loading-spinner loading-xs" aria-hidden="true" />
          Save
        </button>
      </div>
    </form>
  </section>
</template>
