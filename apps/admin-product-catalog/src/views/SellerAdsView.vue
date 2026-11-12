<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, money } from '@/lib/adminApi';
import { createAd, listAds, updateAd } from '@/lib/sellerApi';
import type { AdCampaign, AdCampaignRequest, AdStatus } from '@/types/seller';

/** Sponsored-product campaigns: list with stats, create/edit, pause/resume. */
type Err = { message: string; correlationId: string | null };
const ads = ref<readonly AdCampaign[]>([]);
const loading = ref(false);
const error = ref<Err | null>(null);
const actionError = ref<Err | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    ads.value = await listAds();
  } catch (e: unknown) {
    error.value = errorInfo(e);
    ads.value = [];
  } finally {
    loading.value = false;
  }
}

const modalOpen = ref(false);
const editing = ref<AdCampaign | null>(null);
const submitting = ref(false);
const formError = ref<Err | null>(null);
const touched = ref(false);
const form = reactive({
  productId: '',
  keywords: '',
  categoryId: '',
  bidCpc: '',
  dailyBudget: '',
  currency: 'INR',
  status: 'ACTIVE' as AdStatus,
});

function openModal(ad: AdCampaign | null): void {
  editing.value = ad;
  formError.value = null;
  touched.value = false;
  Object.assign(form, {
    productId: ad?.productId ?? '',
    keywords: ad?.keywords.join(', ') ?? '',
    categoryId: ad?.categoryId ?? '',
    bidCpc: ad ? String(ad.bidCpc) : '',
    dailyBudget: ad ? String(ad.dailyBudget) : '',
    currency: ad?.currency ?? 'INR',
    status: ad?.status === 'EXHAUSTED' ? 'ACTIVE' : (ad?.status ?? 'ACTIVE'),
  });
  modalOpen.value = true;
}

function parseKeywords(v: string): string[] {
  return [...new Set(v.split(',').map((k) => k.trim().toLowerCase()).filter((k) => k.length > 0))];
}

const validationError = computed<string | null>(() => {
  if (form.productId.trim().length === 0) return 'Product ID is required.';
  if (parseKeywords(form.keywords).length === 0 && form.categoryId.trim().length === 0) {
    return 'Provide at least one keyword or a category.';
  }
  const bid = Number(form.bidCpc);
  const budget = Number(form.dailyBudget);
  if (!Number.isFinite(bid) || bid <= 0) return 'Bid (CPC) must be a positive number.';
  if (!Number.isFinite(budget) || budget <= 0) return 'Daily budget must be a positive number.';
  if (budget < bid) return 'Daily budget must be at least one bid.';
  if (form.currency.trim().length !== 3) return 'Currency must be a 3-letter code.';
  return null;
});

function toRequest(): AdCampaignRequest {
  return {
    productId: form.productId.trim(),
    keywords: parseKeywords(form.keywords),
    categoryId: form.categoryId.trim() || undefined,
    bidCpc: Number(form.bidCpc),
    dailyBudget: Number(form.dailyBudget),
    currency: form.currency.trim().toUpperCase(),
    status: form.status,
  };
}

async function submit(): Promise<void> {
  touched.value = true;
  if (validationError.value !== null || submitting.value) return;
  submitting.value = true;
  formError.value = null;
  try {
    if (editing.value) await updateAd(editing.value.id, toRequest());
    else await createAd(toRequest());
    modalOpen.value = false;
    void load();
  } catch (e: unknown) {
    formError.value = errorInfo(e);
  } finally {
    submitting.value = false;
  }
}

async function toggle(ad: AdCampaign): Promise<void> {
  actionError.value = null;
  try {
    await updateAd(ad.id, {
      productId: ad.productId,
      keywords: ad.keywords,
      categoryId: ad.categoryId ?? undefined,
      bidCpc: ad.bidCpc,
      dailyBudget: ad.dailyBudget,
      currency: ad.currency,
      status: ad.status === 'PAUSED' ? 'ACTIVE' : 'PAUSED',
    });
    void load();
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  }
}

function ctr(ad: AdCampaign): string {
  const imp = ad.impressions ?? 0;
  return imp > 0 ? `${(((ad.clicks ?? 0) / imp) * 100).toFixed(1)}%` : '-';
}

const statusClass: Readonly<Record<AdStatus, string>> = {
  ACTIVE: 'badge-success',
  PAUSED: 'badge-ghost',
  EXHAUSTED: 'badge-warning',
};

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Sponsored ads</h1>
      <button class="btn btn-primary btn-sm" type="button" data-test="ad-new" @click="openModal(null)">New campaign</button>
    </div>

    <ProblemAlert v-if="actionError" class="mb-4" :message="actionError.message" :correlation-id="actionError.correlationId" />

    <div v-if="loading" class="data-card p-8 text-center" role="status">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <div v-else-if="ads.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No campaigns yet</h2>
    </div>
    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Ad campaigns</caption>
          <thead>
            <tr>
              <th scope="col">Product</th>
              <th scope="col">Keywords</th>
              <th scope="col">Bid</th>
              <th scope="col">Spent / budget</th>
              <th scope="col">Impr.</th>
              <th scope="col">Clicks</th>
              <th scope="col">CTR</th>
              <th scope="col">Status</th>
              <th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in ads" :key="a.id" data-test="ad-row">
              <td class="font-mono text-xs">{{ a.productId.slice(0, 8) }}</td>
              <td class="text-xs">{{ a.keywords.join(', ') }}</td>
              <td class="text-sm tabular-nums">{{ money(a.bidCpc, a.currency) }}</td>
              <td class="text-sm tabular-nums">{{ money(a.spentToday ?? 0, a.currency) }} / {{ money(a.dailyBudget, a.currency) }}</td>
              <td class="tabular-nums">{{ a.impressions ?? 0 }}</td>
              <td class="tabular-nums">{{ a.clicks ?? 0 }}</td>
              <td class="tabular-nums">{{ ctr(a) }}</td>
              <td><span class="badge badge-sm" :class="statusClass[a.status]">{{ a.status }}</span></td>
              <td class="text-right">
                <div class="flex justify-end gap-2">
                  <button class="btn btn-outline btn-xs" type="button" @click="openModal(a)">Edit</button>
                  <button v-if="a.status !== 'EXHAUSTED'" class="btn btn-outline btn-xs" type="button" @click="toggle(a)">
                    {{ a.status === 'PAUSED' ? 'Resume' : 'Pause' }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="modalOpen" class="modal modal-open" role="dialog" aria-modal="true" data-test="ad-modal">
      <div class="modal-box data-card-elevated">
        <h3 class="text-lg font-semibold">{{ editing ? 'Edit campaign' : 'New campaign' }}</h3>
        <ProblemAlert v-if="formError" class="mt-3" :message="formError.message" :correlation-id="formError.correlationId" />
        <form class="mt-3 grid grid-cols-2 gap-3" novalidate @submit.prevent="submit">
          <label class="form-control col-span-2">
            <span class="label-text">Product ID</span>
            <input v-model="form.productId" class="input input-bordered w-full font-mono" data-test="ad-product" />
          </label>
          <label class="form-control col-span-2">
            <span class="label-text">Keywords <span class="opacity-60">(comma separated)</span></span>
            <input v-model="form.keywords" class="input input-bordered w-full" data-test="ad-keywords" />
          </label>
          <label class="form-control col-span-2">
            <span class="label-text">Category ID <span class="opacity-60">(optional)</span></span>
            <input v-model="form.categoryId" class="input input-bordered w-full font-mono" />
          </label>
          <label class="form-control">
            <span class="label-text">Bid per click</span>
            <input v-model="form.bidCpc" type="number" min="0" step="0.01" class="input input-bordered w-full" />
          </label>
          <label class="form-control">
            <span class="label-text">Daily budget</span>
            <input v-model="form.dailyBudget" type="number" min="0" step="0.01" class="input input-bordered w-full" />
          </label>
          <label class="form-control">
            <span class="label-text">Currency</span>
            <input v-model="form.currency" class="input input-bordered w-full uppercase" maxlength="3" />
          </label>
          <label class="form-control">
            <span class="label-text">Status</span>
            <select v-model="form.status" class="select select-bordered w-full">
              <option value="ACTIVE">Active</option>
              <option value="PAUSED">Paused</option>
            </select>
          </label>
          <p v-if="touched && validationError" class="col-span-2 text-sm text-error" data-test="ad-validation">{{ validationError }}</p>
          <div class="modal-action col-span-2">
            <button class="btn btn-ghost" type="button" :disabled="submitting" @click="modalOpen = false">Cancel</button>
            <button class="btn btn-primary" type="submit" :disabled="submitting" data-test="ad-submit">
              <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
              Save
            </button>
          </div>
        </form>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="modalOpen = false" />
    </div>
  </section>
</template>
