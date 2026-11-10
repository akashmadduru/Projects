<script setup lang="ts">
import { computed, reactive } from 'vue';
import { useRouter } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { useProductMutations } from '@/composables/useProductMutations';
import type { ProductImageRequest } from '@/types/api';

/**
 * A dedicated page for creating a product (as opposed to `ProductFormModal.vue`,
 * which still handles editing in-place from the catalog table). Same validation
 * and submit contract as the modal's create mode, just full-page instead of an
 * overlay — some flows read better with a URL of their own (bookmarkable,
 * back-button-navigable) than as a modal state.
 */
const router = useRouter();
const mutations = useProductMutations();

/** One row of the repeatable image list. Local-only shape; converted to `ProductImageRequest` on submit. */
interface ImageRow {
  url: string;
  displayOrder: string;
}

function blankImageRow(nextOrder: number): ImageRow {
  return { url: '', displayOrder: String(nextOrder) };
}

interface FormState {
  sku: string;
  name: string;
  priceAmount: string;
  priceCurrency: string;
  active: boolean;
  initialQuantity: string;
  sellerName: string;
  specifications: string;
  images: ImageRow[];
}

const form = reactive<FormState>({
  sku: '',
  name: '',
  priceAmount: '',
  priceCurrency: 'USD',
  active: true,
  initialQuantity: '',
  sellerName: '',
  specifications: '',
  images: [],
});
const touched = reactive({
  sku: false,
  name: false,
  priceAmount: false,
  priceCurrency: false,
  initialQuantity: false,
  specifications: false,
  images: false,
});

const CURRENCY_SHAPE = /^[A-Z]{3}$/;

const skuError = computed<string | null>(() =>
  form.sku.trim().length === 0 ? 'SKU is required.' : null,
);
const nameError = computed<string | null>(() =>
  form.name.trim().length === 0 ? 'Name is required.' : null,
);
const priceAmountError = computed<string | null>(() => {
  // Defensive `String(...)` coercion: a `type="number"` input's v-model can
  // hand back a number rather than a string depending on browser/how the
  // value was set (see the identical comment on ProductFormModal.vue).
  if (String(form.priceAmount).trim().length === 0) return 'Price is required.';
  const amount = Number(form.priceAmount);
  if (!Number.isFinite(amount) || amount <= 0) return 'Price must be a positive number.';
  return null;
});
const priceCurrencyError = computed<string | null>(() => {
  const value = String(form.priceCurrency).trim().toUpperCase();
  if (value.length === 0) return 'Currency is required.';
  if (!CURRENCY_SHAPE.test(value)) return 'Currency must be a 3-letter code, e.g. USD.';
  return null;
});
/**
 * Mirrors the backend's `@AssertTrue` on `CreateProductRequest` exactly:
 * `initialQuantity` is only required (and must be `>= 1`) when `active` is
 * checked — an inactive product can omit it entirely, since it has no saga to
 * kick off yet.
 */
const initialQuantityError = computed<string | null>(() => {
  if (!form.active) return null;
  const value = String(form.initialQuantity).trim();
  if (value.length === 0) return 'Initial quantity is required for an active product.';
  const amount = Number(value);
  if (!Number.isInteger(amount) || amount < 1) {
    return 'Initial quantity must be a whole number of at least 1.';
  }
  return null;
});
/**
 * Kept as a plain JSON-object textarea rather than a dynamic key/value
 * editor: `specifications` is an open `Map<String,Object>` bag, and a
 * structured editor for arbitrary nested values would be a second form
 * builder for a field nothing else in this app needs to shape-check beyond
 * "is it a JSON object".
 */
const specificationsError = computed<string | null>(() => {
  const raw = form.specifications.trim();
  if (raw.length === 0) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return 'Specifications must be valid JSON.';
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return 'Specifications must be a JSON object, e.g. {"color": "red"}.';
  }
  return null;
});
const imagesError = computed<string | null>(() => {
  for (const row of form.images) {
    if (row.url.trim().length === 0) return 'Each image row needs a URL, or remove the row.';
    const order = Number(row.displayOrder);
    if (!Number.isInteger(order) || order < 0) {
      return 'Display order must be a non-negative whole number.';
    }
  }
  return null;
});
const formValid = computed<boolean>(
  () =>
    skuError.value === null &&
    nameError.value === null &&
    priceAmountError.value === null &&
    priceCurrencyError.value === null &&
    initialQuantityError.value === null &&
    specificationsError.value === null &&
    imagesError.value === null,
);

function addImageRow(): void {
  form.images.push(blankImageRow(form.images.length));
}

function removeImageRow(index: number): void {
  form.images.splice(index, 1);
}

const submitting = computed<boolean>(() => mutations.createState.value.status === 'submitting');
const serverErrorMessage = computed<string | null>(() => {
  const state = mutations.createState.value;
  return state.status === 'error' ? state.message : null;
});
const serverErrorCorrelationId = computed<string | null>(() => {
  const state = mutations.createState.value;
  return state.status === 'error' ? state.correlationId : null;
});

async function submit(): Promise<void> {
  touched.sku = true;
  touched.name = true;
  touched.priceAmount = true;
  touched.priceCurrency = true;
  touched.initialQuantity = true;
  touched.specifications = true;
  touched.images = true;
  if (!formValid.value || submitting.value) {
    return;
  }

  const sellerName = form.sellerName.trim();
  const specifications =
    form.specifications.trim().length === 0
      ? undefined
      : (JSON.parse(form.specifications.trim()) as Record<string, unknown>);
  const images: readonly ProductImageRequest[] | undefined =
    form.images.length === 0
      ? undefined
      : form.images.map((row) => ({ url: row.url.trim(), displayOrder: Number(row.displayOrder) }));

  const result = await mutations.createProduct({
    sku: form.sku.trim(),
    name: form.name.trim(),
    priceAmount: Number(form.priceAmount),
    priceCurrency: form.priceCurrency.trim().toUpperCase(),
    active: form.active,
    // Omitted (not sent as `null`) when inactive: the backend only requires
    // this at all when `active` is true, and an explicit `null` reads as
    // "no starting stock" rather than "not applicable".
    initialQuantity: form.active ? Number(form.initialQuantity) : undefined,
    sellerName: sellerName.length === 0 ? undefined : sellerName,
    specifications,
    images,
  });
  if (result) {
    await router.push({ name: 'products' });
  }
}

function cancel(): void {
  if (submitting.value) {
    return;
  }
  void router.push({ name: 'products' });
}
</script>

<template>
  <section class="mx-auto max-w-2xl">
    <div class="mb-4 flex items-baseline gap-3">
      <h1 class="text-2xl font-semibold">New product</h1>
    </div>

    <div class="data-card" data-test="new-product-form">
      <div v-if="serverErrorMessage" data-test="new-product-error">
        <ProblemAlert
          class="mb-4"
          :message="serverErrorMessage"
          :correlation-id="serverErrorCorrelationId"
        />
      </div>

      <form class="flex flex-col gap-3" novalidate @submit.prevent="submit">
        <div class="form-control">
          <label class="label" for="new-product-sku">
            <span class="label-text">SKU</span>
          </label>
          <input
            id="new-product-sku"
            v-model="form.sku"
            class="input input-bordered w-full"
            type="text"
            data-test="new-product-sku"
            :aria-invalid="touched.sku && skuError !== null"
            :aria-describedby="touched.sku && skuError ? 'new-product-sku-error' : undefined"
            @blur="touched.sku = true"
          />
          <p
            v-if="touched.sku && skuError"
            id="new-product-sku-error"
            class="mt-1 text-sm text-error"
            data-test="new-product-sku-error"
          >
            {{ skuError }}
          </p>
        </div>

        <div class="form-control">
          <label class="label" for="new-product-name">
            <span class="label-text">Name</span>
          </label>
          <input
            id="new-product-name"
            v-model="form.name"
            class="input input-bordered w-full"
            type="text"
            data-test="new-product-name"
            :aria-invalid="touched.name && nameError !== null"
            :aria-describedby="touched.name && nameError ? 'new-product-name-error' : undefined"
            @blur="touched.name = true"
          />
          <p
            v-if="touched.name && nameError"
            id="new-product-name-error"
            class="mt-1 text-sm text-error"
            data-test="new-product-name-error"
          >
            {{ nameError }}
          </p>
        </div>

        <div class="flex gap-3">
          <div class="form-control flex-1">
            <label class="label" for="new-product-price-amount">
              <span class="label-text">Price amount</span>
            </label>
            <input
              id="new-product-price-amount"
              v-model="form.priceAmount"
              class="input input-bordered w-full"
              type="number"
              min="0"
              step="0.01"
              data-test="new-product-price-amount"
              :aria-invalid="touched.priceAmount && priceAmountError !== null"
              :aria-describedby="
                touched.priceAmount && priceAmountError ? 'new-product-price-amount-error' : undefined
              "
              @blur="touched.priceAmount = true"
            />
            <p
              v-if="touched.priceAmount && priceAmountError"
              id="new-product-price-amount-error"
              class="mt-1 text-sm text-error"
              data-test="new-product-price-amount-error"
            >
              {{ priceAmountError }}
            </p>
          </div>

          <div class="form-control w-28">
            <label class="label" for="new-product-price-currency">
              <span class="label-text">Currency</span>
            </label>
            <input
              id="new-product-price-currency"
              v-model="form.priceCurrency"
              class="input input-bordered w-full uppercase"
              type="text"
              maxlength="3"
              data-test="new-product-price-currency"
              :aria-invalid="touched.priceCurrency && priceCurrencyError !== null"
              :aria-describedby="
                touched.priceCurrency && priceCurrencyError
                  ? 'new-product-price-currency-error'
                  : undefined
              "
              @blur="touched.priceCurrency = true"
            />
            <p
              v-if="touched.priceCurrency && priceCurrencyError"
              id="new-product-price-currency-error"
              class="mt-1 text-sm text-error"
              data-test="new-product-price-currency-error"
            >
              {{ priceCurrencyError }}
            </p>
          </div>
        </div>

        <div class="form-control">
          <label class="label cursor-pointer justify-start gap-3">
            <input
              v-model="form.active"
              type="checkbox"
              class="toggle toggle-primary"
              data-test="new-product-active"
            />
            <span class="label-text">Active</span>
          </label>
        </div>

        <div v-if="form.active" class="form-control">
          <label class="label" for="new-product-initial-quantity">
            <span class="label-text">Initial quantity</span>
          </label>
          <input
            id="new-product-initial-quantity"
            v-model="form.initialQuantity"
            class="input input-bordered w-full"
            type="number"
            min="1"
            step="1"
            data-test="new-product-initial-quantity"
            :aria-invalid="touched.initialQuantity && initialQuantityError !== null"
            :aria-describedby="
              touched.initialQuantity && initialQuantityError
                ? 'new-product-initial-quantity-error'
                : undefined
            "
            @blur="touched.initialQuantity = true"
          />
          <p
            v-if="touched.initialQuantity && initialQuantityError"
            id="new-product-initial-quantity-error"
            class="mt-1 text-sm text-error"
            data-test="new-product-initial-quantity-error"
          >
            {{ initialQuantityError }}
          </p>
        </div>

        <div class="form-control">
          <label class="label" for="new-product-seller-name">
            <span class="label-text">Seller name <span class="opacity-60">(optional)</span></span>
          </label>
          <input
            id="new-product-seller-name"
            v-model="form.sellerName"
            class="input input-bordered w-full"
            type="text"
            data-test="new-product-seller-name"
          />
        </div>

        <div class="form-control">
          <label class="label" for="new-product-specifications">
            <span class="label-text">Specifications <span class="opacity-60">(optional JSON object)</span></span>
          </label>
          <textarea
            id="new-product-specifications"
            v-model="form.specifications"
            class="textarea textarea-bordered w-full font-mono text-sm"
            rows="4"
            placeholder='{"color": "red", "material": "aluminum"}'
            data-test="new-product-specifications"
            :aria-invalid="touched.specifications && specificationsError !== null"
            :aria-describedby="
              touched.specifications && specificationsError
                ? 'new-product-specifications-error'
                : undefined
            "
            @blur="touched.specifications = true"
          />
          <p
            v-if="touched.specifications && specificationsError"
            id="new-product-specifications-error"
            class="mt-1 text-sm text-error"
            data-test="new-product-specifications-error"
          >
            {{ specificationsError }}
          </p>
        </div>

        <div class="form-control">
          <div class="flex items-baseline justify-between">
            <span id="new-product-images-label" class="label-text"
              >Images <span class="opacity-60">(optional)</span></span
            >
            <button
              class="btn btn-outline btn-xs"
              type="button"
              data-test="new-product-image-add"
              @click="addImageRow"
            >
              Add image
            </button>
          </div>
          <div
            v-for="(row, index) in form.images"
            :key="index"
            class="mt-2 flex items-start gap-2"
            data-test="new-product-image-row"
          >
            <input
              v-model="row.url"
              class="input input-bordered input-sm flex-1"
              type="url"
              :aria-label="`Image ${index + 1} URL`"
              placeholder="https://…"
              :data-test="`new-product-image-url-${index}`"
              @blur="touched.images = true"
            />
            <input
              v-model="row.displayOrder"
              class="input input-bordered input-sm w-24"
              type="number"
              min="0"
              step="1"
              :aria-label="`Image ${index + 1} display order`"
              placeholder="Order"
              :data-test="`new-product-image-order-${index}`"
              @blur="touched.images = true"
            />
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              :data-test="`new-product-image-remove-${index}`"
              @click="removeImageRow(index)"
            >
              Remove
            </button>
          </div>
          <p
            v-if="touched.images && imagesError"
            class="mt-1 text-sm text-error"
            data-test="new-product-images-error"
          >
            {{ imagesError }}
          </p>
        </div>

        <div class="mt-2 flex justify-end gap-2">
          <button
            class="btn btn-ghost"
            type="button"
            data-test="new-product-cancel"
            :disabled="submitting"
            @click="cancel"
          >
            Cancel
          </button>
          <button
            class="btn btn-primary"
            type="submit"
            data-test="new-product-submit"
            :disabled="submitting"
          >
            <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
            Create product
          </button>
        </div>
      </form>
    </div>
  </section>
</template>
