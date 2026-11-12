<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import {
  cancelOrder,
  changeOrderStatus,
  errorInfo,
  fmtDate,
  getOrder,
  listOrders,
  money,
} from '@/lib/adminApi';
import {
  ORDER_STATUSES,
  type AdminOrderDetail,
  type AdminOrderSummary,
  type OrderStatus,
} from '@/types/commerce';

/** Admin order management: filter/search/page, detail modal, forward status steps, cancel. */
const PAGE_SIZE = 20;
const FLOW: readonly OrderStatus[] = [
  'PLACED',
  'CONFIRMED',
  'PACKED',
  'SHIPPED',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
];
const CANCELLABLE: readonly OrderStatus[] = ['PLACED', 'CONFIRMED', 'PACKED'];

const rows = ref<readonly AdminOrderSummary[]>([]);
const loading = ref(false);
const error = ref<{ message: string; correlationId: string | null } | null>(null);
const page = ref(0);
const totalPages = ref(1);
const totalElements = ref(0);
// Deep links from the dashboard: /orders?status=SHIPPED or /orders?q=<order id prefix>.
// Validated against the known statuses; anything else is ignored rather than sent to the API.
const route = useRoute();
const queryStatus = typeof route.query.status === 'string' ? route.query.status : '';
const queryTerm = typeof route.query.q === 'string' ? route.query.q.slice(0, 64) : '';
const filters = reactive({
  status: (ORDER_STATUSES as readonly string[]).includes(queryStatus) ? queryStatus : '',
  q: queryTerm,
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const res = await listOrders({
      status: filters.status || undefined,
      q: filters.q.trim() || undefined,
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

function applyFilters(): void {
  page.value = 0;
  void load();
}

function go(delta: number): void {
  page.value = Math.max(0, Math.min(totalPages.value - 1, page.value + delta));
  void load();
}

// ---- detail ----
const detail = ref<AdminOrderDetail | null>(null);
const detailLoading = ref(false);
const detailError = ref<{ message: string; correlationId: string | null } | null>(null);
const acting = ref(false);

async function openDetail(id: string): Promise<void> {
  detail.value = null;
  detailError.value = null;
  detailLoading.value = true;
  resetForms();
  try {
    detail.value = await getOrder(id);
  } catch (e: unknown) {
    detailError.value = errorInfo(e);
  } finally {
    detailLoading.value = false;
  }
}

function closeDetail(): void {
  if (acting.value) return;
  detail.value = null;
  detailError.value = null;
  detailLoading.value = false;
  resetForms();
}

const nextStatus = computed<OrderStatus | null>(() => {
  const d = detail.value;
  if (!d) return null;
  const idx = FLOW.indexOf(d.status);
  return idx >= 0 && idx < FLOW.length - 1 ? (FLOW[idx + 1] ?? null) : null;
});
const canCancel = computed<boolean>(() => !!detail.value && CANCELLABLE.includes(detail.value.status));
const address = computed(() => detail.value?.shipTo ?? null);

const shipForm = reactive({ open: false, trackingNumber: '', carrier: '', note: '' });
const cancelForm = reactive({ open: false, reason: '' });
const stepNote = ref('');

function resetForms(): void {
  shipForm.open = false;
  shipForm.trackingNumber = '';
  shipForm.carrier = '';
  shipForm.note = '';
  cancelForm.open = false;
  cancelForm.reason = '';
  stepNote.value = '';
}

async function act(fn: () => Promise<AdminOrderDetail>): Promise<void> {
  acting.value = true;
  detailError.value = null;
  try {
    detail.value = await fn();
    resetForms();
    void load();
  } catch (e: unknown) {
    detailError.value = errorInfo(e);
  } finally {
    acting.value = false;
  }
}

function advance(): void {
  const d = detail.value;
  const next = nextStatus.value;
  if (!d || !next) return;
  if (next === 'SHIPPED') {
    shipForm.open = true;
    return;
  }
  void act(() =>
    changeOrderStatus(d.id, { status: next, note: stepNote.value.trim() || undefined }),
  );
}

function submitShip(): void {
  const d = detail.value;
  if (!d || shipForm.trackingNumber.trim().length === 0) return;
  void act(() =>
    changeOrderStatus(d.id, {
      status: 'SHIPPED',
      trackingNumber: shipForm.trackingNumber.trim(),
      carrier: shipForm.carrier.trim() || undefined,
      note: shipForm.note.trim() || undefined,
    }),
  );
}

function submitCancel(): void {
  const d = detail.value;
  if (!d || cancelForm.reason.trim().length === 0) return;
  void act(() => cancelOrder(d.id, cancelForm.reason.trim()));
}

function badge(status: string): string {
  if (status === 'DELIVERED') return 'badge-success';
  if (status === 'CANCELLED') return 'badge-error';
  if (status === 'PENDING_PAYMENT') return 'badge-warning';
  return 'badge-info';
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Orders</h1>
      <span v-if="!loading" class="text-sm opacity-70">{{ totalElements }} total</span>
    </div>

    <form class="mb-4 flex flex-wrap items-end gap-3" @submit.prevent="applyFilters">
      <div class="form-control">
        <label class="label" for="orders-status"><span class="label-text">Status</span></label>
        <select
          id="orders-status"
          v-model="filters.status"
          class="select select-bordered select-sm"
          data-test="orders-filter-status"
          @change="applyFilters"
        >
          <option value="">All</option>
          <option v-for="s in ORDER_STATUSES" :key="s" :value="s">{{ s }}</option>
        </select>
      </div>
      <div class="form-control">
        <label class="label" for="orders-q"><span class="label-text">Search (order id / email)</span></label>
        <input
          id="orders-q"
          v-model="filters.q"
          class="input input-bordered input-sm w-64"
          type="search"
          data-test="orders-filter-q"
        />
      </div>
      <button class="btn btn-primary btn-sm" type="submit">Search</button>
    </form>

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading orders…</p>
    </div>

    <div v-else-if="error" data-test="orders-state-error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="rows.length === 0" class="data-card p-10 text-center" data-test="orders-state-empty">
      <h2 class="text-lg font-medium">No orders found</h2>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Orders, page {{ page + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col">Order</th>
              <th scope="col">Customer</th>
              <th scope="col">Status</th>
              <th scope="col">Payment</th>
              <th scope="col" class="text-right">Items</th>
              <th scope="col" class="text-right">Total</th>
              <th scope="col">Placed</th>
              <th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in rows" :key="o.id" data-test="orders-row">
              <td class="font-mono text-sm">{{ o.id.slice(0, 8) }}</td>
              <td>{{ o.email ?? '-' }}</td>
              <td><span class="badge badge-sm" :class="badge(o.status)">{{ o.status }}</span></td>
              <td class="text-sm">{{ o.paymentMethod }} · {{ o.paymentStatus }}</td>
              <td class="text-right tabular-nums">{{ o.itemCount }}</td>
              <td class="text-right tabular-nums">{{ money(o.totalAmount, o.currency) }}</td>
              <td class="text-sm">{{ fmtDate(o.placedAt) }}</td>
              <td class="text-right">
                <button class="btn btn-outline btn-xs" type="button" @click="openDetail(o.id)">
                  View
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="flex items-center justify-between gap-4 border-t border-base-content/10 p-4">
        <span class="text-sm opacity-70">Page {{ page + 1 }} of {{ totalPages }}</span>
        <div class="join">
          <button class="btn btn-sm join-item" type="button" :disabled="page === 0" @click="go(-1)">
            Previous
          </button>
          <button
            class="btn btn-sm join-item"
            type="button"
            :disabled="page + 1 >= totalPages"
            @click="go(1)"
          >
            Next
          </button>
        </div>
      </div>
    </div>

    <!-- detail modal -->
    <div
      v-if="detail || detailLoading || detailError"
      class="modal modal-open"
      role="dialog"
      aria-modal="true"
      data-test="order-detail"
    >
      <div class="modal-box data-card-elevated max-w-3xl">
        <div v-if="detailLoading" class="p-6 text-center">
          <span class="loading loading-spinner loading-lg" aria-hidden="true" />
        </div>

        <ProblemAlert
          v-if="detailError"
          class="mb-3"
          :message="detailError.message"
          :correlation-id="detailError.correlationId"
        />

        <template v-if="detail">
          <div class="flex items-baseline justify-between">
            <h3 class="text-lg font-semibold">Order {{ detail.id.slice(0, 8) }}</h3>
            <span class="badge" :class="badge(detail.status)">{{ detail.status }}</span>
          </div>
          <p class="mt-1 text-sm opacity-70">
            {{ detail.email ?? 'unknown customer' }} · {{ detail.paymentMethod }} ·
            {{ detail.paymentStatus }}
          </p>

          <div class="mt-4 grid gap-4 md:grid-cols-2">
            <div>
              <h4 class="mb-1 text-sm font-semibold">Ship to</h4>
              <p v-if="address" class="text-sm">
                {{ address.fullName }}<br />
                {{ address.line1 }}<span v-if="address.line2">, {{ address.line2 }}</span><br />
                {{ address.city }}, {{ address.state }} {{ address.postalCode }}<br />
                {{ address.country }}<span v-if="address.phone"> · {{ address.phone }}</span>
              </p>
              <p v-else class="text-sm opacity-70">-</p>
              <p v-if="detail.trackingNumber" class="mt-2 text-sm">
                Tracking: <span class="font-mono">{{ detail.trackingNumber }}</span>
                <span v-if="detail.carrier"> ({{ detail.carrier }})</span>
              </p>
              <p v-if="detail.estimatedDeliveryDate" class="text-sm">
                ETA: {{ detail.estimatedDeliveryDate }}
              </p>
            </div>
            <div>
              <h4 class="mb-1 text-sm font-semibold">Totals</h4>
              <dl class="grid grid-cols-2 gap-y-1 text-sm">
                <dt>Subtotal</dt>
                <dd class="text-right">{{ money(detail.subtotalAmount, detail.currency) }}</dd>
                <dt>Discount<span v-if="detail.couponCode"> ({{ detail.couponCode }})</span></dt>
                <dd class="text-right">{{ money(detail.discountAmount, detail.currency) }}</dd>
                <dt>Shipping</dt>
                <dd class="text-right">{{ money(detail.shippingAmount, detail.currency) }}</dd>
                <dt>{{ detail.taxLabel ?? 'Tax' }}</dt>
                <dd class="text-right">{{ money(detail.taxAmount, detail.currency) }}</dd>
                <dt class="font-semibold">Total</dt>
                <dd class="text-right font-semibold">{{ money(detail.totalAmount, detail.currency) }}</dd>
              </dl>
            </div>
          </div>

          <h4 class="mb-1 mt-4 text-sm font-semibold">Items</h4>
          <table class="table-minimal table table-sm">
            <tbody>
              <tr v-for="it in detail.items ?? []" :key="it.productId">
                <td>{{ it.name }}</td>
                <td class="text-right tabular-nums">× {{ it.quantity }}</td>
                <td class="text-right tabular-nums">{{ money(it.unitPrice, detail.currency) }}</td>
              </tr>
            </tbody>
          </table>

          <h4 class="mb-1 mt-4 text-sm font-semibold">Timeline</h4>
          <ol class="space-y-1 text-sm" data-test="order-timeline">
            <li v-for="(t, i) in detail.timeline ?? []" :key="i">
              <span class="font-medium">{{ t.status }}</span>
              <span class="opacity-70"> · {{ fmtDate(t.at) }} · {{ t.actor }}</span>
              <span v-if="t.note" class="opacity-70"> - {{ t.note }}</span>
            </li>
          </ol>

          <!-- ship form -->
          <form
            v-if="shipForm.open"
            class="mt-4 flex flex-col gap-2 rounded border border-base-content/10 p-3"
            @submit.prevent="submitShip"
          >
            <h4 class="text-sm font-semibold">Mark as shipped</h4>
            <input
              v-model="shipForm.trackingNumber"
              class="input input-bordered input-sm"
              placeholder="Tracking number (required)"
              required
              data-test="ship-tracking"
            />
            <input
              v-model="shipForm.carrier"
              class="input input-bordered input-sm"
              placeholder="Carrier"
              data-test="ship-carrier"
            />
            <input v-model="shipForm.note" class="input input-bordered input-sm" placeholder="Note" />
            <div class="flex justify-end gap-2">
              <button class="btn btn-ghost btn-sm" type="button" @click="shipForm.open = false">
                Back
              </button>
              <button class="btn btn-primary btn-sm" type="submit" :disabled="acting">
                <span v-if="acting" class="loading loading-spinner loading-xs" aria-hidden="true" />
                Mark shipped
              </button>
            </div>
          </form>

          <!-- cancel form -->
          <form
            v-else-if="cancelForm.open"
            class="mt-4 flex flex-col gap-2 rounded border border-base-content/10 p-3"
            @submit.prevent="submitCancel"
          >
            <h4 class="text-sm font-semibold">Cancel order</h4>
            <input
              v-model="cancelForm.reason"
              class="input input-bordered input-sm"
              placeholder="Reason (required)"
              required
              data-test="cancel-reason"
            />
            <div class="flex justify-end gap-2">
              <button class="btn btn-ghost btn-sm" type="button" @click="cancelForm.open = false">
                Back
              </button>
              <button class="btn btn-error btn-sm" type="submit" :disabled="acting">
                <span v-if="acting" class="loading loading-spinner loading-xs" aria-hidden="true" />
                Confirm cancel
              </button>
            </div>
          </form>

          <div v-else class="mt-4 flex flex-wrap items-center gap-2">
            <input
              v-if="nextStatus && nextStatus !== 'SHIPPED'"
              v-model="stepNote"
              class="input input-bordered input-sm w-56"
              placeholder="Note (optional)"
            />
            <button
              v-if="nextStatus"
              class="btn btn-primary btn-sm"
              type="button"
              data-test="order-advance"
              :disabled="acting"
              @click="advance"
            >
              <span v-if="acting" class="loading loading-spinner loading-xs" aria-hidden="true" />
              Mark {{ nextStatus }}
            </button>
            <button
              v-if="canCancel"
              class="btn btn-outline btn-error btn-sm"
              type="button"
              data-test="order-cancel"
              :disabled="acting"
              @click="cancelForm.open = true"
            >
              Cancel order
            </button>
          </div>
        </template>

        <div class="modal-action">
          <button class="btn btn-ghost" type="button" :disabled="acting" @click="closeDetail">
            Close
          </button>
        </div>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="closeDetail" />
    </div>
  </section>
</template>
