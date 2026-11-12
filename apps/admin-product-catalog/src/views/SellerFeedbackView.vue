<script setup lang="ts">
import { onMounted, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate } from '@/lib/adminApi';
import { getMySeller, listFeedback } from '@/lib/sellerApi';
import type { Seller, SellerFeedback } from '@/types/seller';

/** Buyer feedback on my seller account (public feed, scoped to my seller id). */
const seller = ref<Seller | null>(null);
const items = ref<readonly SellerFeedback[]>([]);
const page = ref(0);
const totalPages = ref(1);
const loading = ref(false);
const error = ref<{ message: string; correlationId: string | null } | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    seller.value ??= await getMySeller();
    if (seller.value) {
      const res = await listFeedback(seller.value.id, page.value);
      items.value = res.content;
      totalPages.value = Math.max(1, res.page?.totalPages ?? 1);
    }
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
  <section class="mx-auto max-w-3xl">
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Feedback</h1>
      <span v-if="seller" class="text-sm" data-test="feedback-summary">
        {{ seller.ratingAvg?.toFixed(2) ?? '-' }} / 5 ({{ seller.ratingCount ?? 0 }} ratings)
      </span>
    </div>
    <div v-if="loading" class="data-card p-8 text-center" role="status">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <div v-else-if="items.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No feedback yet</h2>
    </div>
    <template v-else>
      <ul class="space-y-3">
        <li v-for="f in items" :key="f.id" class="data-card p-3" data-test="feedback-row">
          <div class="flex items-center justify-between">
            <span class="text-sm font-semibold" :aria-label="`${f.rating} out of 5`">{{ '★'.repeat(f.rating) }}{{ '☆'.repeat(5 - f.rating) }}</span>
            <span class="text-xs opacity-60">{{ fmtDate(f.createdAt) }}</span>
          </div>
          <p v-if="f.comment" class="mt-1 text-sm">{{ f.comment }}</p>
        </li>
      </ul>
      <div class="mt-3 flex items-center justify-end gap-2">
        <button class="btn btn-outline btn-xs" type="button" :disabled="page === 0" @click="go(-1)">Prev</button>
        <span class="text-xs">Page {{ page + 1 }} / {{ totalPages }}</span>
        <button class="btn btn-outline btn-xs" type="button" :disabled="page + 1 >= totalPages" @click="go(1)">Next</button>
      </div>
    </template>
  </section>
</template>
