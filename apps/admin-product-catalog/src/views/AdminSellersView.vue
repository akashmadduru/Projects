<script setup lang="ts">
import { onMounted, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate } from '@/lib/adminApi';
import { adminDecideSeller, adminListSellers, adminSuspendSeller } from '@/lib/sellerApi';
import type { Seller, SellerStatus } from '@/types/seller';

/** Sellers review. KYC is masked: bank account shows last4 only. */
type Err = { message: string; correlationId: string | null };
const STATUSES: readonly (SellerStatus | '')[] = ['', 'SUBMITTED', 'APPROVED', 'REJECTED', 'SUSPENDED', 'DRAFT'];

const sellers = ref<readonly Seller[]>([]);
const status = ref<SellerStatus | ''>('SUBMITTED');
const loading = ref(false);
const error = ref<Err | null>(null);
const actionError = ref<Err | null>(null);
const busy = ref(false);
const expanded = ref<string | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    sellers.value = await adminListSellers(status.value || undefined);
  } catch (e: unknown) {
    error.value = errorInfo(e);
    sellers.value = [];
  } finally {
    loading.value = false;
  }
}

const rejecting = ref<Seller | null>(null);
const reason = ref('');
const reasonTouched = ref(false);

async function run(fn: () => Promise<unknown>): Promise<void> {
  busy.value = true;
  actionError.value = null;
  try {
    await fn();
    rejecting.value = null;
    void load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    busy.value = false;
  }
}

function approve(s: Seller): void {
  void run(() => adminDecideSeller(s.id, true));
}

function confirmReject(): void {
  reasonTouched.value = true;
  const s = rejecting.value;
  if (!s || reason.value.trim().length === 0) return;
  void run(() => adminDecideSeller(s.id, false, reason.value.trim()));
}

function suspend(s: Seller): void {
  void run(() => adminSuspendSeller(s.id));
}

function openReject(s: Seller): void {
  rejecting.value = s;
  reason.value = '';
  reasonTouched.value = false;
}

const badge: Readonly<Record<SellerStatus, string>> = {
  DRAFT: 'badge-ghost',
  SUBMITTED: 'badge-info',
  APPROVED: 'badge-success',
  REJECTED: 'badge-error',
  SUSPENDED: 'badge-warning',
};

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-baseline justify-between gap-2">
      <h1 class="text-2xl font-semibold">Sellers</h1>
      <select v-model="status" class="select select-bordered select-sm" aria-label="Filter by status" data-test="seller-filter" @change="load">
        <option v-for="s in STATUSES" :key="s" :value="s">{{ s === '' ? 'All' : s }}</option>
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
    <div v-else-if="sellers.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No sellers</h2>
    </div>
    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Sellers</caption>
          <thead>
            <tr>
              <th scope="col">Seller</th>
              <th scope="col">GSTIN / PAN</th>
              <th scope="col">Bank</th>
              <th scope="col">Rating</th>
              <th scope="col">Status</th>
              <th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="s in sellers" :key="s.id">
              <tr data-test="seller-row">
                <td>
                  <button class="text-left" type="button" @click="expanded = expanded === s.id ? null : s.id">
                    <div class="text-sm font-medium">{{ s.displayName }}</div>
                    <div class="text-xs opacity-70">{{ s.legalName }}</div>
                  </button>
                </td>
                <td class="font-mono text-xs">{{ s.gstin ?? '-' }}<br />{{ s.pan ?? '-' }}</td>
                <td class="font-mono text-xs">
                  {{ s.bankAccountLast4 ? `•••• ${s.bankAccountLast4}` : '-' }}<br />{{ s.ifsc ?? '' }}
                </td>
                <td class="text-sm">{{ s.ratingAvg?.toFixed(1) ?? '-' }} ({{ s.ratingCount ?? 0 }})</td>
                <td><span class="badge badge-sm" :class="badge[s.status]">{{ s.status }}</span></td>
                <td class="text-right">
                  <div class="flex justify-end gap-2">
                    <button
                      v-if="s.status === 'SUBMITTED' || s.status === 'REJECTED' || s.status === 'SUSPENDED'"
                      class="btn btn-outline btn-success btn-xs"
                      type="button"
                      :disabled="busy"
                      data-test="seller-approve"
                      @click="approve(s)"
                    >
                      {{ s.status === 'SUBMITTED' ? 'Approve' : 'Re-approve' }}
                    </button>
                    <button
                      v-if="s.status === 'SUBMITTED'"
                      class="btn btn-outline btn-error btn-xs"
                      type="button"
                      :disabled="busy"
                      data-test="seller-reject"
                      @click="openReject(s)"
                    >
                      Reject
                    </button>
                    <button
                      v-if="s.status === 'APPROVED'"
                      class="btn btn-outline btn-warning btn-xs"
                      type="button"
                      :disabled="busy"
                      data-test="seller-suspend"
                      @click="suspend(s)"
                    >
                      Suspend
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="expanded === s.id">
                <td colspan="6" class="bg-base-200/40 text-xs">
                  <div class="grid grid-cols-2 gap-x-6 gap-y-1 sm:grid-cols-4">
                    <div><span class="opacity-60">Email:</span> {{ s.email ?? '-' }}</div>
                    <div><span class="opacity-60">Phone:</span> {{ s.phone ?? '-' }}</div>
                    <div><span class="opacity-60">Commission:</span> {{ s.commissionPercent ?? '-' }}%</div>
                    <div><span class="opacity-60">Applied:</span> {{ fmtDate(s.createdAt) }}</div>
                    <div class="col-span-2 sm:col-span-4">
                      <span class="opacity-60">Pickup:</span>
                      <template v-if="s.pickupAddress">
                        {{ s.pickupAddress.line1 }}, {{ s.pickupAddress.city }}, {{ s.pickupAddress.state }} {{ s.pickupAddress.pincode }}
                      </template>
                      <template v-else>-</template>
                    </div>
                    <div v-if="s.rejectionReason" class="col-span-2 sm:col-span-4 text-error">Rejection: {{ s.rejectionReason }}</div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="rejecting" class="modal modal-open" role="dialog" aria-modal="true">
      <div class="modal-box data-card-elevated">
        <h3 class="text-lg font-semibold">Reject {{ rejecting.displayName }}</h3>
        <form class="mt-3 grid gap-3" novalidate @submit.prevent="confirmReject">
          <label class="form-control">
            <span class="label-text">Reason (sent to the applicant)</span>
            <textarea v-model="reason" class="textarea textarea-bordered w-full" rows="3" maxlength="500" data-test="reject-reason" />
          </label>
          <p v-if="reasonTouched && reason.trim().length === 0" class="text-sm text-error">A reason is required.</p>
          <div class="modal-action">
            <button class="btn btn-ghost" type="button" :disabled="busy" @click="rejecting = null">Cancel</button>
            <button class="btn btn-error" type="submit" :disabled="busy" data-test="reject-confirm">Reject</button>
          </div>
        </form>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="rejecting = null" />
    </div>
  </section>
</template>
