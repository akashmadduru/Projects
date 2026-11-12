<script setup lang="ts">
import { onMounted, ref } from 'vue';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate, money } from '@/lib/adminApi';
import { adminListPayouts, adminRunPayouts } from '@/lib/sellerApi';
import type { Payout, PayoutRunResult } from '@/types/seller';

/** Payout batch: run (simulated bank transfer) + list. */
type Err = { message: string; correlationId: string | null };
const payouts = ref<readonly Payout[]>([]);
const loading = ref(false);
const error = ref<Err | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    payouts.value = await adminListPayouts();
  } catch (e: unknown) {
    error.value = errorInfo(e);
    payouts.value = [];
  } finally {
    loading.value = false;
  }
}

const confirming = ref(false);
const minAgeDays = ref('');
const running = ref(false);
const runError = ref<Err | null>(null);
const runResult = ref<PayoutRunResult | null>(null);

async function run(): Promise<void> {
  const raw = minAgeDays.value.trim();
  const days = raw.length === 0 ? undefined : Number(raw);
  if (days !== undefined && (!Number.isInteger(days) || days < 0)) {
    confirming.value = false;
    runError.value = { message: 'Min age (days) must be a whole number, 0 or more.', correlationId: null };
    return;
  }
  running.value = true;
  runError.value = null;
  runResult.value = null;
  try {
    runResult.value = await adminRunPayouts(days);
    void load();
  } catch (e: unknown) {
    runError.value = errorInfo(e);
  } finally {
    running.value = false;
    confirming.value = false;
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-end justify-between gap-2">
      <h1 class="text-2xl font-semibold">Payouts</h1>
      <div class="flex items-end gap-2">
        <label class="form-control">
          <span class="label-text text-xs">Min entry age (days, blank = default 7)</span>
          <input v-model="minAgeDays" type="number" min="0" step="1" class="input input-bordered input-sm w-40" data-test="payout-minage" />
        </label>
        <button class="btn btn-primary btn-sm" type="button" :disabled="running" data-test="payout-run" @click="confirming = true">
          Run payouts
        </button>
      </div>
    </div>

    <ProblemAlert v-if="runError" class="mb-4" :message="runError.message" :correlation-id="runError.correlationId" />
    <div v-if="runResult" class="alert alert-success mb-4" role="status" data-test="payout-result">
      Payout run complete<template v-if="(runResult.payouts ?? runResult.count) !== undefined">
        : {{ runResult.payouts ?? runResult.count }} payout(s) created</template>.
    </div>

    <div v-if="loading" class="data-card p-8 text-center" role="status">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <div v-else-if="payouts.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No payouts yet</h2>
    </div>
    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Payouts</caption>
          <thead>
            <tr><th scope="col">Date</th><th scope="col">Seller</th><th scope="col">Reference</th><th scope="col">Amount</th><th scope="col">Status</th></tr>
          </thead>
          <tbody>
            <tr v-for="p in payouts" :key="p.id" data-test="payout-row">
              <td class="text-xs">{{ fmtDate(p.createdAt) }}</td>
              <td class="text-sm">{{ p.sellerName ?? p.sellerId?.slice(0, 8) ?? '-' }}</td>
              <td class="font-mono text-xs">{{ p.reference }}</td>
              <td class="text-sm tabular-nums">{{ money(p.amount, p.currency) }}</td>
              <td><span class="badge badge-sm badge-success">{{ p.status }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <ConfirmDialog
      :open="confirming"
      title="Run payouts"
      message="Create payouts for every seller's unsettled ledger entries older than the minimum age? This simulates the bank transfer and cannot be undone."
      confirm-label="Run payouts"
      :submitting="running"
      @confirm="run"
      @cancel="confirming = false"
    />
  </section>
</template>
