<script setup lang="ts">
import { onMounted, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { decideModeration, errorInfo, fmtDate, listModeration, money } from '@/lib/adminApi';
import type { ModerationItem, ModerationStatus } from '@/types/commerce';

/**
 * Review queue for products listed by marketplace sellers (TD-P9-03). A seller's new product is
 * hidden until approved here. The warnings are hints for the reviewer, never a decision.
 */
const PAGE_SIZE = 20;
const FLAG_LABELS: Readonly<Record<string, string>> = {
  NO_IMAGE: 'No image',
  NO_DESCRIPTION: 'No description',
  BANNED_TERM: 'Banned term',
  PRICE_OUTLIER: 'Unusual price',
};

type ErrorInfo = { message: string; correlationId: string | null };

const status = ref<ModerationStatus>('PENDING_REVIEW');
const rows = ref<readonly ModerationItem[]>([]);
const loading = ref(false);
const error = ref<ErrorInfo | null>(null);
const page = ref(0);
const totalPages = ref(1);
const totalElements = ref(0);
const busy = ref<string | null>(null);
const actionError = ref<ErrorInfo | null>(null);
const rejecting = ref<ModerationItem | null>(null);
const rejectNote = ref('');

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const res = await listModeration({ status: status.value, page: page.value, size: PAGE_SIZE });
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

function changeStatus(): void {
  page.value = 0;
  void load();
}

function go(delta: number): void {
  page.value = Math.max(0, Math.min(totalPages.value - 1, page.value + delta));
  void load();
}

async function approve(item: ModerationItem): Promise<void> {
  busy.value = item.id;
  actionError.value = null;
  try {
    await decideModeration(item.id, 'APPROVE');
    await load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    busy.value = null;
  }
}

function startReject(item: ModerationItem): void {
  rejecting.value = item;
  rejectNote.value = '';
}

async function confirmReject(): Promise<void> {
  const item = rejecting.value;
  if (!item || !rejectNote.value.trim()) return;
  busy.value = item.id;
  actionError.value = null;
  try {
    await decideModeration(item.id, 'REJECT', rejectNote.value.trim());
    rejecting.value = null;
    await load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    busy.value = null;
  }
}

onMounted(load);
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-2xl font-semibold">Product review</h1>
        <p class="text-sm opacity-70">Products listed by sellers stay hidden until approved.</p>
      </div>
      <select
        v-model="status"
        class="select select-bordered select-sm"
        aria-label="Filter by review status"
        @change="changeStatus"
      >
        <option value="PENDING_REVIEW">Waiting for review</option>
        <option value="REJECTED">Rejected</option>
        <option value="APPROVED">Approved</option>
      </select>
    </div>

    <ProblemAlert v-if="actionError" class="mb-3" :message="actionError.message" :correlation-id="actionError.correlationId" />

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="rows.length === 0" class="data-card p-10 text-center" data-test="moderation-empty">
      <h2 class="text-lg font-medium">Nothing here</h2>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Products, page {{ page + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col">Product</th>
              <th scope="col">Seller</th>
              <th scope="col" class="text-right">Price</th>
              <th scope="col">Warnings</th>
              <th scope="col">Listed</th>
              <th v-if="status === 'PENDING_REVIEW'" scope="col"><span class="sr-only">Actions</span></th>
              <th v-else scope="col">Note</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id" data-test="moderation-row">
              <td>
                <div class="flex items-center gap-3">
                  <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" alt="" class="h-10 w-10 rounded object-cover" />
                  <div>
                    <div class="font-medium">{{ item.name }}</div>
                    <div class="font-mono text-xs opacity-70">{{ item.sku }}</div>
                  </div>
                </div>
              </td>
              <td class="text-sm">{{ item.sellerName ?? item.sellerId.slice(0, 8) }}</td>
              <td class="text-right tabular-nums">{{ money(item.price, item.currency) }}</td>
              <td>
                <span v-if="item.flags.length === 0" class="text-xs opacity-60">None</span>
                <span v-for="f in item.flags" :key="f" class="badge badge-warning badge-sm mr-1">{{ FLAG_LABELS[f] ?? f }}</span>
              </td>
              <td class="text-sm">{{ fmtDate(item.createdAt) }}</td>
              <td v-if="status === 'PENDING_REVIEW'" class="whitespace-nowrap text-right">
                <button class="btn btn-primary btn-xs" type="button" :disabled="busy === item.id" @click="approve(item)">Approve</button>
                <button class="btn btn-ghost btn-xs ml-1 text-error" type="button" :disabled="busy === item.id" @click="startReject(item)">Reject</button>
              </td>
              <td v-else class="text-sm">{{ item.note ?? '' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="flex items-center justify-between border-t border-base-300 px-4 py-3 text-sm">
        <span>{{ totalElements }} product(s)</span>
        <div class="join">
          <button class="btn join-item btn-sm" type="button" :disabled="page === 0" @click="go(-1)">Previous</button>
          <button class="btn join-item btn-sm" type="button" :disabled="page >= totalPages - 1" @click="go(1)">Next</button>
        </div>
      </div>
    </div>

    <div v-if="rejecting" class="modal modal-open" role="dialog" aria-modal="true" @keydown.esc="rejecting = null">
      <div class="modal-box data-card-elevated max-w-md">
        <h3 class="text-lg font-semibold">Reject “{{ rejecting.name }}”</h3>
        <label class="mt-3 block text-sm" for="reject-note">Note for the seller (required)</label>
        <textarea id="reject-note" v-model="rejectNote" class="textarea textarea-bordered mt-1 w-full" rows="3" maxlength="500" />
        <div class="modal-action">
          <button class="btn" type="button" @click="rejecting = null">Cancel</button>
          <button class="btn btn-error" type="button" :disabled="!rejectNote.trim() || busy === rejecting.id" @click="confirmReject()">Reject</button>
        </div>
      </div>
    </div>
  </section>
</template>
