<script setup lang="ts">
import { onMounted, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate, money } from '@/lib/adminApi';
import { listLedger, listMyPayouts } from '@/lib/sellerApi';
import type { LedgerEntry, Payout } from '@/types/seller';

/** Ledger (paged) + payouts. */
type Err = { message: string; correlationId: string | null };
const ledger = ref<readonly LedgerEntry[]>([]);
const payouts = ref<readonly Payout[]>([]);
const page = ref(0);
const totalPages = ref(1);
const loading = ref(false);
const error = ref<Err | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const [l, p] = await Promise.all([listLedger(page.value), listMyPayouts()]);
    ledger.value = l.content;
    totalPages.value = Math.max(1, l.page?.totalPages ?? 1);
    payouts.value = p;
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    loading.value = false;
  }
}

function go(delta: number): void {
  const n = page.value + delta;
  if (n < 0 || n >= totalPages.value) return;
  page.value = n;
  void load();
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <h1 class="mb-4 text-2xl font-semibold">Ledger &amp; payouts</h1>
    <div v-if="loading" class="data-card p-8 text-center" role="status">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <template v-else>
      <h2 class="mb-2 text-lg font-medium">Payouts</h2>
      <div v-if="payouts.length === 0" class="data-card mb-6 p-6 text-center text-sm opacity-70">No payouts yet.</div>
      <div v-else class="data-card mb-6 overflow-x-auto p-0">
        <table class="table-minimal table">
          <caption class="sr-only">Payouts</caption>
          <thead>
            <tr><th scope="col">Date</th><th scope="col">Reference</th><th scope="col">Amount</th><th scope="col">Status</th></tr>
          </thead>
          <tbody>
            <tr v-for="p in payouts" :key="p.id" data-test="payout-row">
              <td class="text-xs">{{ fmtDate(p.createdAt) }}</td>
              <td class="font-mono text-xs">{{ p.reference }}</td>
              <td class="text-sm tabular-nums">{{ money(p.amount, p.currency) }}</td>
              <td><span class="badge badge-sm badge-success">{{ p.status }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>

      <h2 class="mb-2 text-lg font-medium">Ledger</h2>
      <div v-if="ledger.length === 0" class="data-card p-6 text-center text-sm opacity-70">No ledger entries yet.</div>
      <div v-else class="data-card overflow-x-auto p-0">
        <table class="table-minimal table">
          <caption class="sr-only">Ledger</caption>
          <thead>
            <tr><th scope="col">Date</th><th scope="col">Type</th><th scope="col">Order</th><th scope="col">Amount</th><th scope="col">Settled</th></tr>
          </thead>
          <tbody>
            <tr v-for="e in ledger" :key="e.id" data-test="ledger-row">
              <td class="text-xs">{{ fmtDate(e.createdAt) }}</td>
              <td class="text-sm">{{ e.type }}</td>
              <td class="font-mono text-xs">{{ e.orderId ? e.orderId.slice(0, 8) : '-' }}</td>
              <td class="text-sm tabular-nums" :class="e.amount < 0 ? 'text-error' : ''">{{ money(e.amount, e.currency) }}</td>
              <td class="text-xs">{{ e.payoutId ? 'Paid out' : 'Pending' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="mt-3 flex items-center justify-end gap-2">
        <button class="btn btn-outline btn-xs" type="button" :disabled="page === 0" @click="go(-1)">Prev</button>
        <span class="text-xs">Page {{ page + 1 }} / {{ totalPages }}</span>
        <button class="btn btn-outline btn-xs" type="button" :disabled="page + 1 >= totalPages" @click="go(1)">Next</button>
      </div>
    </template>
  </section>
</template>
