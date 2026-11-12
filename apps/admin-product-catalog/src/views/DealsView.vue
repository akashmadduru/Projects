<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import {
  createDeal,
  deactivateDeal,
  errorInfo,
  fmtDate,
  getProduct,
  listDeals,
  money,
  updateDeal,
} from '@/lib/adminApi';
import { apiFetch } from '@/lib/http';
import type { PagedModel, Product, ProductVariant } from '@/types/api';
import type { Deal, DealRequest } from '@/types/commerce';

/** Deals admin: list, create/edit (modal), deactivate. Backed by promotion-service /api/admin/deals. */
const deals = ref<readonly Deal[]>([]);
const loading = ref(false);
const error = ref<{ message: string; correlationId: string | null } | null>(null);

/** Product names for display + the picker (first 200 products; larger catalogs = tech debt). */
const products = ref<readonly Product[]>([]);
const productName = computed(() => new Map(products.value.map((p) => [p.id, p.name])));

async function loadProducts(): Promise<void> {
  try {
    const res = await apiFetch<PagedModel<Product>>('/api/products', {
      query: { page: 0, size: 200, sort: 'name,asc' },
    });
    products.value = res.content;
  } catch {
    products.value = [];
  }
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    deals.value = await listDeals();
  } catch (e: unknown) {
    error.value = errorInfo(e);
    deals.value = [];
  } finally {
    loading.value = false;
  }
}

interface FormState {
  productId: string;
  variantId: string;
  title: string;
  dealPrice: string;
  currency: string;
  startsAt: string;
  endsAt: string;
  quantityLimit: string;
  active: boolean;
}

function blank(): FormState {
  return {
    productId: '',
    variantId: '',
    title: '',
    dealPrice: '',
    currency: 'USD',
    startsAt: '',
    endsAt: '',
    quantityLimit: '',
    active: true,
  };
}

function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const modalOpen = ref(false);
const editing = ref<Deal | null>(null);
const form = reactive<FormState>(blank());
const submitting = ref(false);
const formError = ref<{ message: string; correlationId: string | null } | null>(null);
const touched = ref(false);
const variants = ref<readonly ProductVariant[]>([]);

async function loadVariants(productId: string): Promise<void> {
  variants.value = [];
  if (!productId) return;
  try {
    const p = await getProduct(productId);
    if (form.productId === productId) variants.value = p.variants ?? [];
  } catch {
    variants.value = [];
  }
}

function onProductChange(): void {
  form.variantId = '';
  void loadVariants(form.productId);
}

function openCreate(): void {
  editing.value = null;
  Object.assign(form, blank());
  variants.value = [];
  formError.value = null;
  touched.value = false;
  modalOpen.value = true;
}

function openEdit(d: Deal): void {
  editing.value = d;
  Object.assign(form, {
    productId: d.productId,
    variantId: d.variantId ?? '',
    title: d.title ?? '',
    dealPrice: String(d.dealPrice),
    currency: d.currency,
    startsAt: toLocalInput(d.startsAt),
    endsAt: toLocalInput(d.endsAt),
    quantityLimit: d.quantityLimit === null ? '' : String(d.quantityLimit),
    active: d.active,
  } satisfies FormState);
  formError.value = null;
  touched.value = false;
  modalOpen.value = true;
  void loadVariants(d.productId);
}

function closeModal(): void {
  if (!submitting.value) modalOpen.value = false;
}

const validationError = computed<string | null>(() => {
  if (!form.productId) return 'Select a product.';
  const price = Number(form.dealPrice);
  if (form.dealPrice.trim().length === 0 || !Number.isFinite(price) || price <= 0) {
    return 'Deal price must be a positive number.';
  }
  if (form.currency.trim().length !== 3) return 'Currency must be a 3-letter code.';
  if (!form.startsAt || !form.endsAt) return 'Start and end dates are required.';
  if (new Date(form.endsAt) <= new Date(form.startsAt)) return 'End date must be after the start date.';
  const q = form.quantityLimit.trim();
  if (q.length === 0 || !Number.isInteger(Number(q)) || Number(q) < 1) {
    return 'Quantity limit is required (whole number of at least 1).';
  }
  return null;
});

async function submit(): Promise<void> {
  touched.value = true;
  if (validationError.value !== null || submitting.value) return;
  const body: DealRequest = {
    productId: form.productId,
    variantId: form.variantId || null,
    title: form.title.trim() || undefined,
    dealPrice: Number(form.dealPrice),
    currency: form.currency.trim().toUpperCase(),
    startsAt: new Date(form.startsAt).toISOString(),
    endsAt: new Date(form.endsAt).toISOString(),
    quantityLimit: form.quantityLimit.trim() ? Number(form.quantityLimit) : undefined,
    active: form.active,
  };
  submitting.value = true;
  formError.value = null;
  try {
    if (editing.value) {
      await updateDeal(editing.value.id, body);
    } else {
      await createDeal(body);
    }
    modalOpen.value = false;
    void load();
  } catch (e: unknown) {
    formError.value = errorInfo(e);
  } finally {
    submitting.value = false;
  }
}

const deactivating = ref<Deal | null>(null);
const deactivateBusy = ref(false);
const deactivateError = ref<{ message: string; correlationId: string | null } | null>(null);

async function confirmDeactivate(): Promise<void> {
  const target = deactivating.value;
  if (!target) return;
  deactivateBusy.value = true;
  deactivateError.value = null;
  try {
    await deactivateDeal(target.id);
    deactivating.value = null;
    void load();
  } catch (e: unknown) {
    deactivating.value = null;
    deactivateError.value = errorInfo(e);
  } finally {
    deactivateBusy.value = false;
  }
}

function dealState(d: Deal): { label: string; cls: string } {
  if (!d.active) return { label: 'Inactive', cls: 'badge-ghost' };
  const now = Date.now();
  if (new Date(d.endsAt).getTime() < now) return { label: 'Ended', cls: 'badge-warning' };
  if (new Date(d.startsAt).getTime() > now) return { label: 'Scheduled', cls: 'badge-info' };
  return { label: 'Live', cls: 'badge-success' };
}

onMounted(() => {
  void loadProducts();
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Deals</h1>
      <button class="btn btn-primary btn-sm" type="button" data-test="deal-new" @click="openCreate">
        New deal
      </button>
    </div>

    <ProblemAlert
      v-if="deactivateError"
      class="mb-4"
      :message="deactivateError.message"
      :correlation-id="deactivateError.correlationId"
    />

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading deals…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="deals.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No deals yet</h2>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Deals</caption>
          <thead>
            <tr>
              <th scope="col">Deal</th>
              <th scope="col">Price</th>
              <th scope="col">Window</th>
              <th scope="col">Claimed</th>
              <th scope="col">Status</th>
              <th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in deals" :key="d.id" data-test="deal-row">
              <td>
                <div class="text-sm font-semibold">{{ d.title || productName.get(d.productId) || d.productId }}</div>
                <div class="text-xs opacity-70">
                  {{ productName.get(d.productId) ?? d.productId.slice(0, 8) }}
                  <span v-if="d.variantId"> · variant {{ d.variantId.slice(0, 8) }}</span>
                </div>
              </td>
              <td class="text-sm tabular-nums">{{ money(d.dealPrice, d.currency) }}</td>
              <td class="text-xs">{{ fmtDate(d.startsAt) }}<br />→ {{ fmtDate(d.endsAt) }}</td>
              <td class="text-sm tabular-nums">{{ d.claimed }} / {{ d.quantityLimit ?? '∞' }}</td>
              <td>
                <span class="badge badge-sm" :class="dealState(d).cls">{{ dealState(d).label }}</span>
              </td>
              <td class="text-right">
                <div class="flex justify-end gap-2">
                  <button class="btn btn-outline btn-xs" type="button" @click="openEdit(d)">Edit</button>
                  <button
                    v-if="d.active"
                    class="btn btn-outline btn-error btn-xs"
                    type="button"
                    data-test="deal-deactivate"
                    @click="deactivating = d"
                  >
                    Deactivate
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="modalOpen" class="modal modal-open" role="dialog" aria-modal="true" data-test="deal-modal">
      <div class="modal-box data-card-elevated">
        <h3 class="text-lg font-semibold">{{ editing ? 'Edit deal' : 'New deal' }}</h3>
        <ProblemAlert
          v-if="formError"
          class="mt-3"
          :message="formError.message"
          :correlation-id="formError.correlationId"
        />
        <form class="mt-3 grid grid-cols-2 gap-3" novalidate @submit.prevent="submit">
          <label class="form-control col-span-2">
            <span class="label-text">Product</span>
            <select
              v-model="form.productId"
              class="select select-bordered w-full"
              data-test="deal-product"
              @change="onProductChange"
            >
              <option value="">Select a product…</option>
              <option v-if="form.productId && !productName.has(form.productId)" :value="form.productId">
                Current ({{ form.productId.slice(0, 8) }})
              </option>
              <option v-for="p in products" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
          </label>
          <label class="form-control col-span-2">
            <span class="label-text">Variant <span class="opacity-60">(optional, blank = whole product)</span></span>
            <select v-model="form.variantId" class="select select-bordered w-full" data-test="deal-variant">
              <option value="">All variants</option>
              <option v-if="form.variantId && !variants.some((v) => v.id === form.variantId)" :value="form.variantId">
                Current ({{ form.variantId.slice(0, 8) }})
              </option>
              <option v-for="v in variants" :key="v.id" :value="v.id">
                {{ v.sku }}
              </option>
            </select>
          </label>
          <label class="form-control col-span-2">
            <span class="label-text">Title <span class="opacity-60">(optional)</span></span>
            <input v-model="form.title" class="input input-bordered w-full" maxlength="120" />
          </label>
          <label class="form-control">
            <span class="label-text">Deal price</span>
            <input v-model="form.dealPrice" class="input input-bordered w-full" type="number" min="0" step="0.01" data-test="deal-price" />
          </label>
          <label class="form-control">
            <span class="label-text">Currency</span>
            <input v-model="form.currency" class="input input-bordered w-full uppercase" maxlength="3" data-test="deal-currency" />
          </label>
          <label class="form-control">
            <span class="label-text">Starts at</span>
            <input v-model="form.startsAt" class="input input-bordered w-full" type="datetime-local" />
          </label>
          <label class="form-control">
            <span class="label-text">Ends at</span>
            <input v-model="form.endsAt" class="input input-bordered w-full" type="datetime-local" />
          </label>
          <label class="form-control">
            <span class="label-text">Quantity limit</span>
            <input v-model="form.quantityLimit" class="input input-bordered w-full" type="number" min="1" step="1" />
          </label>
          <label class="flex items-center gap-2 self-end">
            <input v-model="form.active" type="checkbox" class="checkbox checkbox-sm" />
            <span class="label-text">Active</span>
          </label>
          <p v-if="touched && validationError" class="col-span-2 text-sm text-error" data-test="deal-validation">
            {{ validationError }}
          </p>
          <div class="modal-action col-span-2">
            <button class="btn btn-ghost" type="button" :disabled="submitting" @click="closeModal">Cancel</button>
            <button class="btn btn-primary" type="submit" :disabled="submitting" data-test="deal-submit">
              <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
              {{ editing ? 'Save changes' : 'Create deal' }}
            </button>
          </div>
        </form>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="closeModal" />
    </div>

    <ConfirmDialog
      :open="deactivating !== null"
      title="Deactivate deal"
      :message="deactivating ? `Deactivate this deal? It will stop applying immediately.` : ''"
      confirm-label="Deactivate"
      :submitting="deactivateBusy"
      @confirm="confirmDeactivate"
      @cancel="deactivating = null"
    />
  </section>
</template>
