<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate, money } from '@/lib/adminApi';
import { listSellerShipments, setShipmentStatus } from '@/lib/sellerApi';
import type { SellerShipment } from '@/types/seller';

/** My shipments: confirm -> pack -> ship (tracking) -> out for delivery -> deliver. */
type Err = { message: string; correlationId: string | null };

const NEXT: Readonly<Record<string, { status: string; label: string }>> = {
  PLACED: { status: 'CONFIRMED', label: 'Confirm' },
  CONFIRMED: { status: 'PACKED', label: 'Mark packed' },
  PACKED: { status: 'SHIPPED', label: 'Ship' },
  SHIPPED: { status: 'OUT_FOR_DELIVERY', label: 'Out for delivery' },
  OUT_FOR_DELIVERY: { status: 'DELIVERED', label: 'Mark delivered' },
};

const FILTERS = ['', 'PLACED', 'CONFIRMED', 'PACKED', 'SHIPPED', 'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED'] as const;

const shipments = ref<readonly SellerShipment[]>([]);
const filter = ref<string>('');
const loading = ref(false);
const error = ref<Err | null>(null);
const actionError = ref<Err | null>(null);
const busyId = ref<string | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    shipments.value = await listSellerShipments(filter.value || undefined);
  } catch (e: unknown) {
    error.value = errorInfo(e);
    shipments.value = [];
  } finally {
    loading.value = false;
  }
}

const shipping = ref<SellerShipment | null>(null);
const ship = reactive({ trackingNumber: '', carrier: '' });
const shipTouched = ref(false);

async function advance(s: SellerShipment, extra?: { trackingNumber: string; carrier: string }): Promise<void> {
  const next = NEXT[s.status];
  if (!next) return;
  busyId.value = s.id;
  actionError.value = null;
  try {
    await setShipmentStatus(s.id, {
      status: next.status,
      trackingNumber: extra?.trackingNumber,
      carrier: extra?.carrier || undefined,
    });
    shipping.value = null;
    void load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    busyId.value = null;
  }
}

function onAdvance(s: SellerShipment): void {
  if (NEXT[s.status]?.status === 'SHIPPED') {
    shipping.value = s;
    ship.trackingNumber = s.trackingNumber ?? '';
    ship.carrier = s.carrier ?? '';
    shipTouched.value = false;
  } else {
    void advance(s);
  }
}

function confirmShip(): void {
  shipTouched.value = true;
  if (ship.trackingNumber.trim().length === 0 || !shipping.value) return;
  void advance(shipping.value, { trackingNumber: ship.trackingNumber.trim(), carrier: ship.carrier.trim() });
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-baseline justify-between gap-2">
      <h1 class="text-2xl font-semibold">Orders to fulfil</h1>
      <select v-model="filter" class="select select-bordered select-sm" aria-label="Filter by status" data-test="ship-filter" @change="load">
        <option v-for="f in FILTERS" :key="f" :value="f">{{ f === '' ? 'All statuses' : f }}</option>
      </select>
    </div>

    <ProblemAlert v-if="actionError" class="mb-4" :message="actionError.message" :correlation-id="actionError.correlationId" />

    <div v-if="loading" class="data-card p-8 text-center" role="status">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <div v-else-if="shipments.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No shipments</h2>
    </div>
    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Shipments</caption>
          <thead>
            <tr>
              <th scope="col">Shipment</th>
              <th scope="col">Items</th>
              <th scope="col">Amount</th>
              <th scope="col">Tracking</th>
              <th scope="col">Status</th>
              <th scope="col" class="text-right">Action</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in shipments" :key="s.id" data-test="ship-row">
              <td>
                <div class="font-mono text-xs">{{ s.orderNumber ?? s.orderId ?? s.id }}</div>
                <div class="text-xs opacity-70">{{ fmtDate(s.placedAt ?? s.createdAt) }}</div>
              </td>
              <td class="text-xs">
                <div v-for="(it, i) in s.items ?? []" :key="i">{{ it.quantity }} x {{ it.productName ?? it.name }}</div>
              </td>
              <td class="text-sm tabular-nums">{{ s.amount !== undefined ? money(s.amount, s.currency ?? 'INR') : '-' }}</td>
              <td class="text-xs">{{ s.carrier ?? '' }} {{ s.trackingNumber ?? '-' }}</td>
              <td><span class="badge badge-sm badge-ghost">{{ s.status }}</span></td>
              <td class="text-right">
                <button
                  v-if="NEXT[s.status]"
                  class="btn btn-outline btn-xs"
                  type="button"
                  :disabled="busyId === s.id"
                  data-test="ship-advance"
                  @click="onAdvance(s)"
                >
                  {{ NEXT[s.status]?.label }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="shipping" class="modal modal-open" role="dialog" aria-modal="true">
      <div class="modal-box data-card-elevated">
        <h3 class="text-lg font-semibold">Ship shipment</h3>
        <form class="mt-3 grid gap-3" novalidate @submit.prevent="confirmShip">
          <label class="form-control">
            <span class="label-text">Tracking number</span>
            <input v-model="ship.trackingNumber" class="input input-bordered w-full" data-test="ship-tracking" />
          </label>
          <label class="form-control">
            <span class="label-text">Carrier <span class="opacity-60">(optional)</span></span>
            <input v-model="ship.carrier" class="input input-bordered w-full" />
          </label>
          <p v-if="shipTouched && ship.trackingNumber.trim().length === 0" class="text-sm text-error">Tracking number is required.</p>
          <div class="modal-action">
            <button class="btn btn-ghost" type="button" @click="shipping = null">Cancel</button>
            <button class="btn btn-primary" type="submit" :disabled="busyId !== null" data-test="ship-confirm">Mark shipped</button>
          </div>
        </form>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="shipping = null" />
    </div>
  </section>
</template>
