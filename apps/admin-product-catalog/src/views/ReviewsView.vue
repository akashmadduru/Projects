<script setup lang="ts">
import { onMounted, ref } from 'vue';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import { deleteRating, errorInfo, fmtDate, listRatings } from '@/lib/adminApi';
import type { AdminRating } from '@/types/commerce';

/**
 * Review moderation. Delete is permanent; rating-service publishes the event
 * that recomputes the product's average and count.
 */
const PAGE_SIZE = 20;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type ErrorInfo = { message: string; correlationId: string | null };

const rows = ref<readonly AdminRating[]>([]);
const loading = ref(false);
const error = ref<ErrorInfo | null>(null);
const actionError = ref<ErrorInfo | null>(null);
const page = ref(0);
const totalPages = ref(1);
const totalElements = ref(0);
const q = ref('');
const stars = ref('');
const productId = ref('');
const productIdInvalid = ref(false);
const expanded = ref<Record<string, boolean>>({});

const toDelete = ref<AdminRating | null>(null);
const deleting = ref(false);

async function load(): Promise<void> {
  const pid = productId.value.trim();
  productIdInvalid.value = pid !== '' && !UUID_RE.test(pid);
  if (productIdInvalid.value) return;
  loading.value = true;
  error.value = null;
  try {
    const res = await listRatings({
      q: q.value.trim() || undefined,
      rating: stars.value ? Number(stars.value) : undefined,
      productId: pid || undefined,
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

async function confirmDelete(): Promise<void> {
  if (!toDelete.value) return;
  deleting.value = true;
  actionError.value = null;
  try {
    await deleteRating(toDelete.value.id);
    toDelete.value = null;
    await load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
    toDelete.value = null;
  } finally {
    deleting.value = false;
  }
}

function starString(n: number): string {
  return '★'.repeat(n) + '☆'.repeat(Math.max(0, 5 - n));
}

onMounted(load);
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-end justify-between gap-3">
      <h1 class="text-2xl font-semibold">Reviews</h1>
      <form class="flex flex-wrap items-center gap-2" @submit.prevent="applyFilter">
        <input
          v-model="q"
          class="input input-bordered input-sm w-52"
          type="search"
          placeholder="Search text or reviewer"
          aria-label="Search reviews"
        />
        <input
          v-model="productId"
          class="input input-bordered input-sm w-72 font-mono"
          :class="{ 'input-error': productIdInvalid }"
          placeholder="Product ID (optional)"
          aria-label="Filter by product id"
        />
        <select v-model="stars" class="select select-bordered select-sm" aria-label="Filter by stars" @change="applyFilter">
          <option value="">All ratings</option>
          <option v-for="n in [5, 4, 3, 2, 1]" :key="n" :value="String(n)">{{ n }} star{{ n > 1 ? 's' : '' }}</option>
        </select>
        <button class="btn btn-sm" type="submit">Search</button>
      </form>
    </div>
    <p v-if="productIdInvalid" class="mb-3 text-sm text-error">Product ID must be a UUID.</p>

    <ProblemAlert v-if="actionError" class="mb-4" :message="actionError.message" :correlation-id="actionError.correlationId" />

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading reviews…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="rows.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No reviews match</h2>
    </div>

    <div v-else class="flex flex-col gap-3">
      <article v-for="r in rows" :key="r.id" class="data-card" data-test="review-row">
        <div class="flex flex-wrap items-start justify-between gap-3">
          <div class="min-w-0">
            <p class="text-warning" :aria-label="`${r.ratingValue} out of 5`">{{ starString(r.ratingValue) }}</p>
            <h3 class="font-medium">{{ r.reviewTitle || '(no title)' }}</h3>
            <p class="text-xs opacity-70">
              {{ r.userDisplayName }} · {{ fmtDate(r.createdAt) }}
              <span v-if="r.isVerifiedPurchase" class="badge badge-success badge-xs ml-1">verified</span>
              · 👍 {{ r.helpfulCount }} · 👎 {{ r.unhelpfulCount }}
            </p>
            <p class="font-mono text-xs opacity-50">product {{ r.productId }}</p>
          </div>
          <button
            class="btn btn-outline btn-error btn-xs"
            type="button"
            data-test="review-delete"
            @click="toDelete = r"
          >
            Delete
          </button>
        </div>
        <p
          v-if="r.reviewText"
          class="mt-2 whitespace-pre-line text-sm"
          :class="{ 'line-clamp-3': !expanded[r.id] }"
        >{{ r.reviewText }}</p>
        <button
          v-if="r.reviewText && r.reviewText.length > 240"
          class="link link-hover mt-1 text-xs"
          type="button"
          @click="expanded[r.id] = !expanded[r.id]"
        >
          {{ expanded[r.id] ? 'Show less' : 'Show more' }}
        </button>
      </article>

      <div class="flex items-center justify-between gap-4">
        <span class="text-sm opacity-70">{{ totalElements }} reviews · page {{ page + 1 }} of {{ totalPages }}</span>
        <div class="join">
          <button class="btn btn-sm join-item" type="button" :disabled="page === 0" @click="go(-1)">Previous</button>
          <button class="btn btn-sm join-item" type="button" :disabled="page + 1 >= totalPages" @click="go(1)">
            Next
          </button>
        </div>
      </div>
    </div>

    <ConfirmDialog
      :open="toDelete !== null"
      title="Delete this review?"
      :message="`The review by ${toDelete?.userDisplayName ?? ''} will be removed permanently and the product rating recalculated.`"
      confirm-label="Delete review"
      :submitting="deleting"
      @confirm="confirmDelete"
      @cancel="toDelete = null"
    />
  </section>
</template>
