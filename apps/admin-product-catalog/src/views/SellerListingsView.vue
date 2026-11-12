<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, money } from '@/lib/adminApi';
import {
  createOffer,
  createSellerProduct,
  listOffers,
  listSellerWarehouses,
  setStock,
  updateOffer,
} from '@/lib/sellerApi';
import type { Offer, Warehouse } from '@/types/seller';

/** Seller listings: offers, create offer on existing product, create product + offer, price and per-warehouse stock. */
type Err = { message: string; correlationId: string | null };
const offers = ref<readonly Offer[]>([]);
const warehouses = ref<readonly Warehouse[]>([]);
const loading = ref(false);
const error = ref<Err | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const [o, w] = await Promise.all([listOffers(), listSellerWarehouses().catch(() => [] as readonly Warehouse[])]);
    offers.value = o;
    warehouses.value = w;
  } catch (e: unknown) {
    error.value = errorInfo(e);
    offers.value = [];
  } finally {
    loading.value = false;
  }
}

type Mode = 'offer' | 'product' | 'edit' | 'stock';
const mode = ref<Mode | null>(null);
const target = ref<Offer | null>(null);
const submitting = ref(false);
const formError = ref<Err | null>(null);
const touched = ref(false);

const form = reactive({
  productId: '',
  variantId: '',
  name: '',
  description: '',
  categoryId: '',
  brand: '',
  sku: '',
  imageUrl: '',
  price: '',
  salePrice: '',
  currency: 'INR',
  dispatchDays: '1',
  quantity: '',
  warehouseCode: '',
  active: true,
});

function open(m: Mode, offer: Offer | null = null): void {
  mode.value = m;
  target.value = offer;
  formError.value = null;
  touched.value = false;
  Object.assign(form, {
    productId: '',
    variantId: '',
    name: '',
    description: '',
    categoryId: '',
    brand: '',
    sku: '',
    imageUrl: '',
    price: offer ? String(offer.price) : '',
    salePrice: offer?.salePrice ? String(offer.salePrice) : '',
    currency: offer?.currency ?? 'INR',
    dispatchDays: String(offer?.dispatchDays ?? 1),
    quantity: '',
    warehouseCode: warehouses.value[0]?.code ?? '',
    active: offer?.active ?? true,
  });
}

function close(): void {
  if (!submitting.value) mode.value = null;
}

function num(v: string): number | undefined {
  const t = v.trim();
  return t.length === 0 ? undefined : Number(t);
}

const validationError = computed<string | null>(() => {
  const m = mode.value;
  if (m === 'stock') {
    const q = num(form.quantity);
    if (form.warehouseCode.trim().length === 0) return 'Warehouse code is required.';
    if (q === undefined || !Number.isInteger(q) || q < 0) return 'Quantity must be a whole number, 0 or more.';
    return null;
  }
  if (m === 'offer' && form.productId.trim().length === 0) return 'Product ID is required.';
  if (m === 'product' && form.name.trim().length === 0) return 'Product name is required.';
  const price = num(form.price);
  if (price === undefined || !Number.isFinite(price) || price <= 0) return 'Price must be a positive number.';
  const sale = num(form.salePrice);
  if (sale !== undefined && (!Number.isFinite(sale) || sale <= 0 || sale >= price)) {
    return 'Sale price must be positive and below the price.';
  }
  if (form.currency.trim().length !== 3) return 'Currency must be a 3-letter code.';
  const d = num(form.dispatchDays);
  if (d !== undefined && (!Number.isInteger(d) || d < 0)) return 'Dispatch days must be a whole number.';
  return null;
});

async function submit(): Promise<void> {
  touched.value = true;
  if (validationError.value !== null || submitting.value || mode.value === null) return;
  submitting.value = true;
  formError.value = null;
  try {
    const price = Number(form.price);
    const common = {
      price,
      salePrice: num(form.salePrice),
      currency: form.currency.trim().toUpperCase(),
      dispatchDays: num(form.dispatchDays),
    };
    if (mode.value === 'offer') {
      await createOffer({
        ...common,
        productId: form.productId.trim(),
        variantId: form.variantId.trim() || undefined,
      });
    } else if (mode.value === 'product') {
      await createSellerProduct({
        ...common,
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        categoryId: form.categoryId.trim() || undefined,
        brand: form.brand.trim() || undefined,
        sku: form.sku.trim() || undefined,
        imageUrl: form.imageUrl.trim() || undefined,
        quantity: num(form.quantity),
        warehouseCode: form.warehouseCode.trim() || undefined,
      });
    } else if (mode.value === 'edit' && target.value) {
      await updateOffer(target.value.id, { ...common, active: form.active });
    } else if (mode.value === 'stock' && target.value) {
      await setStock({
        offerId: target.value.id,
        warehouseCode: form.warehouseCode.trim(),
        quantity: Number(form.quantity),
      });
    }
    mode.value = null;
    void load();
  } catch (e: unknown) {
    formError.value = errorInfo(e);
  } finally {
    submitting.value = false;
  }
}

const titles: Readonly<Record<Mode, string>> = {
  offer: 'Add offer on an existing product',
  product: 'Create new product + offer',
  edit: 'Edit offer',
  stock: 'Set stock for a warehouse',
};

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-baseline justify-between gap-2">
      <h1 class="text-2xl font-semibold">My listings</h1>
      <div class="flex gap-2">
        <button class="btn btn-outline btn-sm" type="button" data-test="offer-new" @click="open('offer')">Add offer</button>
        <button class="btn btn-primary btn-sm" type="button" data-test="product-new" @click="open('product')">New product</button>
      </div>
    </div>

    <div v-if="loading" class="data-card p-8 text-center" role="status">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <div v-else-if="offers.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No listings yet</h2>
      <p class="text-sm opacity-70">Create a product, add an offer on an existing one, or bulk-upload a CSV.</p>
    </div>
    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Offers</caption>
          <thead>
            <tr>
              <th scope="col">Product</th>
              <th scope="col">Price</th>
              <th scope="col">Dispatch</th>
              <th scope="col">Stock</th>
              <th scope="col">Status</th>
              <th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in offers" :key="o.id" data-test="offer-row">
              <td>
                <div class="text-sm font-medium">{{ o.productName ?? o.productId }}</div>
                <div v-if="o.sku" class="font-mono text-xs opacity-70">{{ o.sku }}</div>
              </td>
              <td class="text-sm tabular-nums">
                <span v-if="o.salePrice" class="mr-1 line-through opacity-50">{{ money(o.price, o.currency) }}</span>
                {{ money(o.salePrice ?? o.price, o.currency) }}
              </td>
              <td class="text-sm">{{ o.dispatchDays ?? 1 }}d</td>
              <td class="text-sm tabular-nums">{{ o.availableQuantity ?? '-' }}</td>
              <td>
                <span class="badge badge-sm" :class="o.active ? 'badge-success' : 'badge-ghost'">
                  {{ o.active ? 'Active' : 'Inactive' }}
                </span>
              </td>
              <td class="text-right">
                <div class="flex justify-end gap-2">
                  <button class="btn btn-outline btn-xs" type="button" @click="open('edit', o)">Price</button>
                  <button class="btn btn-outline btn-xs" type="button" data-test="offer-stock" @click="open('stock', o)">Stock</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="mode" class="modal modal-open" role="dialog" aria-modal="true" data-test="listing-modal">
      <div class="modal-box data-card-elevated">
        <h3 class="text-lg font-semibold">{{ titles[mode] }}</h3>
        <ProblemAlert v-if="formError" class="mt-3" :message="formError.message" :correlation-id="formError.correlationId" />
        <form class="mt-3 grid grid-cols-2 gap-3" novalidate @submit.prevent="submit">
          <template v-if="mode === 'offer'">
            <label class="form-control col-span-2">
              <span class="label-text">Product ID</span>
              <input v-model="form.productId" class="input input-bordered w-full font-mono" />
            </label>
            <label class="form-control col-span-2">
              <span class="label-text">Variant ID <span class="opacity-60">(optional)</span></span>
              <input v-model="form.variantId" class="input input-bordered w-full font-mono" />
            </label>
          </template>

          <template v-if="mode === 'product'">
            <label class="form-control col-span-2">
              <span class="label-text">Name</span>
              <input v-model="form.name" class="input input-bordered w-full" maxlength="200" data-test="product-name" />
            </label>
            <label class="form-control col-span-2">
              <span class="label-text">Description</span>
              <textarea v-model="form.description" class="textarea textarea-bordered w-full" rows="2" />
            </label>
            <label class="form-control">
              <span class="label-text">Category ID</span>
              <input v-model="form.categoryId" class="input input-bordered w-full font-mono" />
            </label>
            <label class="form-control">
              <span class="label-text">Brand</span>
              <input v-model="form.brand" class="input input-bordered w-full" />
            </label>
            <label class="form-control">
              <span class="label-text">SKU</span>
              <input v-model="form.sku" class="input input-bordered w-full font-mono" />
            </label>
            <label class="form-control">
              <span class="label-text">Image URL</span>
              <input v-model="form.imageUrl" class="input input-bordered w-full" />
            </label>
          </template>

          <template v-if="mode !== 'stock'">
            <label class="form-control">
              <span class="label-text">Price</span>
              <input v-model="form.price" type="number" min="0" step="0.01" class="input input-bordered w-full" data-test="offer-price" />
            </label>
            <label class="form-control">
              <span class="label-text">Sale price <span class="opacity-60">(optional)</span></span>
              <input v-model="form.salePrice" type="number" min="0" step="0.01" class="input input-bordered w-full" />
            </label>
            <label class="form-control">
              <span class="label-text">Currency</span>
              <input v-model="form.currency" class="input input-bordered w-full uppercase" maxlength="3" />
            </label>
            <label class="form-control">
              <span class="label-text">Dispatch days</span>
              <input v-model="form.dispatchDays" type="number" min="0" step="1" class="input input-bordered w-full" />
            </label>
          </template>

          <template v-if="mode === 'stock' || mode === 'product'">
            <label class="form-control">
              <span class="label-text">Warehouse</span>
              <select v-if="warehouses.length > 0" v-model="form.warehouseCode" class="select select-bordered w-full">
                <option v-for="w in warehouses" :key="w.code" :value="w.code">{{ w.code }} - {{ w.name }}</option>
              </select>
              <input v-else v-model="form.warehouseCode" class="input input-bordered w-full uppercase" placeholder="WH-BLR" />
            </label>
            <label class="form-control">
              <span class="label-text">Quantity</span>
              <input v-model="form.quantity" type="number" min="0" step="1" class="input input-bordered w-full" data-test="stock-qty" />
            </label>
          </template>

          <label v-if="mode === 'edit'" class="col-span-2 flex items-center gap-2">
            <input v-model="form.active" type="checkbox" class="checkbox checkbox-sm" />
            <span class="label-text">Offer active</span>
          </label>

          <p v-if="touched && validationError" class="col-span-2 text-sm text-error" data-test="listing-validation">
            {{ validationError }}
          </p>
          <div class="modal-action col-span-2">
            <button class="btn btn-ghost" type="button" :disabled="submitting" @click="close">Cancel</button>
            <button class="btn btn-primary" type="submit" :disabled="submitting" data-test="listing-submit">
              <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
              Save
            </button>
          </div>
        </form>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="close" />
    </div>
  </section>
</template>
