<script setup lang="ts">
import { onMounted, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate, getPayment, listPayments, money, statusBadge } from '@/lib/adminApi';
import { PAYMENT_STATUSES, type AdminPayment, type AdminPaymentDetail } from '@/types/commerce';

/**
 * Read-only payment ledger. Refunds are issued from the Returns screen
 * (order-service drives payment-service), never from here.
 */
const PAGE_SIZE = 20;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type ErrorInfo = { message: string; correlationId: string | null };

const rows = ref<readonly AdminPayment[]>([]);
const loading = ref(false);
const error = ref<ErrorInfo | null>(null);
const page = ref(0);
const totalPages = ref(1);
const totalElements = ref(0);
const status = ref('');
const method = ref('');
const orderId = ref('');
const orderIdInvalid = ref(false);

const detail = ref<AdminPaymentDetail | null>(null);
const detailLoading = ref(false);
const detailError = ref<ErrorInfo | null>(null);

async function load(): Promise<void> {
  const oid = orderId.value.trim();
  orderIdInvalid.value = oid !== '' && !UUID_RE.test(oid);
  if (orderIdInvalid.value) return;
  loading.value = true;
  error.value = null;
  try {
    const res = await listPayments({
      status: status.value || undefined,
      method: method.value || undefined,
      orderId: oid || undefined,
      page: page.value,
      size: PAGE_SIZE,
    });
    rows.value = res.content;
    totalPages.value = Math.max(res.page.totalPages, 1);
    totalElements.value = res.page.totalElements;
  } catch (e: unknown) {
    error.value = errorInfo(e);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

function applyFilter(): void {
  page.value = 0;
  void load();
}

function go(delta: number): void {
  page.value = Math.max(0, Math.min(totalPages.value - 1, page.value + delta));
  void load();
}

async function open(p: AdminPayment): Promise<void> {
  detail.value = { payment: p, refunds: [] };
  detailLoading.value = true;
  detailError.value = null;
  try {
    detail.value = await getPayment(p.id);
  } catch (e: unknown) {
    detailError.value = errorInfo(e);
  } finally {
    detailLoading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-end justify-between gap-3">
      <h1 class="text-2xl font-semibold">Payments</h1>
      <form class="flex flex-wrap items-center gap-2" @submit.prevent="applyFilter">
        <input
          v-model="orderId"
          class="input input-bordered input-sm w-72 font-mono"
          :class="{ 'input-error': orderIdInvalid }"
          placeholder="Order ID (optional)"
          aria-label="Filter by order id"
        />
        <select v-model="status" class="select select-bordered select-sm" aria-label="Filter by status" @change="applyFilter">
          <option value="">All statuses</option>
          <option v-for="s in PAYMENT_STATUSES" :key="s" :value="s">{{ s }}</option>
        </select>
        <select v-model="method" class="select select-bordered select-sm" aria-label="Filter by method" @change="applyFilter">
          <option value="">All methods</option>
          <option value="CARD">CARD</option>
          <option value="COD">COD</option>
        </select>
        <button class="btn btn-sm" type="submit">Search</button>
      </form>
    </div>
    <p v-if="orderIdInvalid" class="mb-3 text-sm text-error">Order ID must be a UUID.</p>

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading payments…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="rows.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No payments match</h2>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Payments, page {{ page + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col">Payment</th>
              <th scope="col">Order</th>
              <th scope="col">Method</th>
              <th scope="col">Status</th>
              <th scope="col" class="text-right">Amount</th>
              <th scope="col" class="text-right">Refunded</th>
              <th scope="col">Created</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="p in rows"
              :key="p.id"
              class="hover cursor-pointer"
              data-test="payments-row"
              tabindex="0"
              @click="open(p)"
              @keydown.enter="open(p)"
            >
              <td class="font-mono text-xs">{{ p.id.slice(0, 8) }}</td>
              <td class="font-mono text-xs">{{ p.orderId.slice(0, 8) }}</td>
              <td class="text-sm">{{ p.method }}<span v-if="p.mode === 'SIMULATED'" class="badge badge-ghost badge-xs ml-1">sim</span></td>
              <td><span class="badge badge-sm" :class="statusBadge(p.status)">{{ p.status }}</span></td>
              <td class="text-right tabular-nums">{{ money(p.amount, p.currency) }}</td>
              <td class="text-right tabular-nums">{{ p.refundedAmount > 0 ? money(p.refundedAmount, p.currency) : '-' }}</td>
              <td class="text-sm">{{ fmtDate(p.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="flex items-center justify-between gap-4 border-t border-base-content/10 p-4">
        <span class="text-sm opacity-70">{{ totalElements }} payments · page {{ page + 1 }} of {{ totalPages }}</span>
        <div class="join">
          <button class="btn btn-sm join-item" type="button" :disabled="page === 0" @click="go(-1)">Previous</button>
          <button class="btn btn-sm join-item" type="button" :disabled="page + 1 >= totalPages" @click="go(1)">
            Next
          </button>
        </div>
      </div>
    </div>

    <div v-if="detail" class="modal modal-open" role="dialog" aria-modal="true" @keydown.esc="detail = null">
      <div class="modal-box data-card-elevated max-w-lg">
        <h3 class="text-lg font-semibold">Payment {{ detail.payment.id.slice(0, 8) }}</h3>
        <ProblemAlert v-if="detailError" class="mt-2" :message="detailError.message" :correlation-id="detailError.correlationId" />
        <dl class="mt-3 grid grid-cols-[8rem_1fr] gap-x-4 gap-y-1 text-sm">
          <dt class="opacity-70">Payment ID</dt><dd class="font-mono text-xs">{{ detail.payment.id }}</dd>
          <dt class="opacity-70">Order ID</dt><dd class="font-mono text-xs">{{ detail.payment.orderId }}</dd>
          <dt class="opacity-70">Customer ID</dt><dd class="font-mono text-xs">{{ detail.payment.userId }}</dd>
          <dt class="opacity-70">Method</dt><dd>{{ detail.payment.method }} ({{ detail.payment.mode }})</dd>
          <dt class="opacity-70">Status</dt><dd><span class="badge badge-sm" :class="statusBadge(detail.payment.status)">{{ detail.payment.status }}</span></dd>
          <dt class="opacity-70">Amount</dt><dd class="tabular-nums">{{ money(detail.payment.amount, detail.payment.currency) }}</dd>
          <dt class="opacity-70">Refunded</dt><dd class="tabular-nums">{{ money(detail.payment.refundedAmount, detail.payment.currency) }}</dd>
          <dt class="opacity-70">Created</dt><dd>{{ fmtDate(detail.payment.createdAt) }}</dd>
          <dt class="opacity-70">Updated</dt><dd>{{ fmtDate(detail.payment.updatedAt) }}</dd>
        </dl>
        <h4 class="mt-4 text-sm font-medium uppercase tracking-wide opacity-60">Refunds</h4>
        <div v-if="detailLoading" class="py-3"><span class="loading loading-dots loading-sm" /></div>
        <p v-else-if="detail.refunds.length === 0" class="text-sm opacity-70">None.</p>
        <ul v-else class="mt-1 flex flex-col gap-1 text-sm">
          <li v-for="r in detail.refunds" :key="r.id" class="flex justify-between gap-3">
            <span>{{ fmtDate(r.createdAt) }} <span class="opacity-70">{{ r.reason ?? '' }}</span></span>
            <span class="tabular-nums">{{ money(r.amount, detail.payment.currency) }}</span>
          </li>
        </ul>
        <div class="modal-action">
          <button class="btn" type="button" @click="detail = null">Close</button>
        </div>
      </div>
    </div>
  </section>
</template>
