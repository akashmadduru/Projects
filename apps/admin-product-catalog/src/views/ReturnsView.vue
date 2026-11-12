<script setup lang="ts">
import { onMounted, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import {
  decideReturn,
  errorInfo,
  fmtDate,
  listReturns,
  money,
  setReturnStatus,
} from '@/lib/adminApi';
import { RETURN_STATUSES, type AdminReturn } from '@/types/commerce';

/** Admin returns queue: approve/reject with note, mark picked up, mark refunded. */
const PAGE_SIZE = 20;

const rows = ref<readonly AdminReturn[]>([]);
const loading = ref(false);
const error = ref<{ message: string; correlationId: string | null } | null>(null);
const actionError = ref<{ message: string; correlationId: string | null } | null>(null);
const page = ref(0);
const totalPages = ref(1);
const status = ref('');
const busyId = ref<string | null>(null);
const notes = ref<Record<string, string>>({});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const res = await listReturns({ status: status.value || undefined, page: page.value, size: PAGE_SIZE });
    rows.value = res.content;
    totalPages.value = Math.max(res.page.totalPages, 1);
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

async function run(id: string, fn: () => Promise<AdminReturn>): Promise<void> {
  busyId.value = id;
  actionError.value = null;
  try {
    await fn();
    await load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    busyId.value = null;
  }
}

function decide(r: AdminReturn, approve: boolean): void {
  void run(r.id, () => decideReturn(r.id, approve, notes.value[r.id]?.trim() || undefined));
}

function mark(r: AdminReturn, next: 'PICKED_UP' | 'REFUNDED'): void {
  void run(r.id, () => setReturnStatus(r.id, next));
}

function badge(s: string): string {
  if (s === 'REFUNDED') return 'badge-success';
  if (s === 'REJECTED') return 'badge-error';
  if (s === 'REQUESTED') return 'badge-warning';
  return 'badge-info';
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Returns</h1>
      <select
        v-model="status"
        class="select select-bordered select-sm"
        aria-label="Filter by status"
        data-test="returns-filter-status"
        @change="applyFilter"
      >
        <option value="">All statuses</option>
        <option v-for="s in RETURN_STATUSES" :key="s" :value="s">{{ s }}</option>
      </select>
    </div>

    <ProblemAlert
      v-if="actionError"
      class="mb-4"
      :message="actionError.message"
      :correlation-id="actionError.correlationId"
    />

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading returns…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="rows.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No return requests</h2>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Returns, page {{ page + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col">Order</th>
              <th scope="col">Status</th>
              <th scope="col">Items</th>
              <th scope="col">Reason</th>
              <th scope="col" class="text-right">Refund</th>
              <th scope="col">Requested</th>
              <th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id" data-test="returns-row">
              <td class="font-mono text-sm">{{ r.orderId.slice(0, 8) }}</td>
              <td><span class="badge badge-sm" :class="badge(r.status)">{{ r.status }}</span></td>
              <td class="text-sm">
                <div v-for="it in r.items" :key="it.productId">{{ it.name }} × {{ it.quantity }}</div>
              </td>
              <td class="text-sm">
                {{ r.reason }}
                <div v-if="r.comment" class="opacity-70">{{ r.comment }}</div>
                <div v-if="r.adminNote" class="opacity-70">Admin: {{ r.adminNote }}</div>
              </td>
              <td class="text-right tabular-nums">{{ r.refundAmount === null ? '-' : money(r.refundAmount, 'INR') }}</td>
              <td class="text-sm">{{ fmtDate(r.createdAt) }}</td>
              <td class="text-right">
                <div class="flex flex-col items-end gap-1">
                  <template v-if="r.status === 'REQUESTED'">
                    <input
                      v-model="notes[r.id]"
                      class="input input-bordered input-xs w-40"
                      placeholder="Note (optional)"
                      aria-label="Decision note"
                    />
                    <div class="flex gap-1">
                      <button
                        class="btn btn-primary btn-xs"
                        type="button"
                        data-test="return-approve"
                        :disabled="busyId === r.id"
                        @click="decide(r, true)"
                      >
                        Approve
                      </button>
                      <button
                        class="btn btn-outline btn-error btn-xs"
                        type="button"
                        data-test="return-reject"
                        :disabled="busyId === r.id"
                        @click="decide(r, false)"
                      >
                        Reject
                      </button>
                    </div>
                  </template>
                  <button
                    v-else-if="r.status === 'APPROVED'"
                    class="btn btn-outline btn-xs"
                    type="button"
                    data-test="return-picked-up"
                    :disabled="busyId === r.id"
                    @click="mark(r, 'PICKED_UP')"
                  >
                    Mark picked up
                  </button>
                  <button
                    v-else-if="r.status === 'PICKED_UP'"
                    class="btn btn-primary btn-xs"
                    type="button"
                    data-test="return-refunded"
                    :disabled="busyId === r.id"
                    @click="mark(r, 'REFUNDED')"
                  >
                    Mark refunded
                  </button>
                </div>
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
  </section>
</template>
