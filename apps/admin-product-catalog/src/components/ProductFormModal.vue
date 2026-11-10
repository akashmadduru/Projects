<script setup lang="ts">
import { computed, reactive, watch } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { useProductMutations } from '@/composables/useProductMutations';
import type { Product, ProductImageRequest } from '@/types/api';

/**
 * Handles both create and edit: `product` present means edit (SKU locked,
 * `version` carried through for the optimistic-lock `PUT`), absent means create.
 */
const props = defineProps<{
  open: boolean;
  product?: Product | null;
}>();

const emit = defineEmits<{
  /** Fired after a successful create/update; parent reloads the list and closes the modal. */
  saved: [product: Product];
  close: [];
}>();

const mutations = useProductMutations();

const isEditing = computed<boolean>(() => props.product != null);

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
  sellerName: string;
  specifications: string;
  images: ImageRow[];
}

function blankForm(): FormState {
  return {
    sku: '',
    name: '',
    priceAmount: '',
    priceCurrency: 'USD',
    active: true,
    sellerName: '',
    specifications: '',
    images: [],
  };
}

function formFromProduct(product: Product): FormState {
  return {
    sku: product.sku,
    name: product.name,
    priceAmount: String(product.priceAmount),
    priceCurrency: product.priceCurrency,
    active: product.active,
    sellerName: product.sellerName ?? '',
    specifications: product.specifications ? JSON.stringify(product.specifications, null, 2) : '',
    images: product.images.map((image) => ({ url: image.url, displayOrder: String(image.displayOrder) })),
  };
}

const form = reactive<FormState>(blankForm());
const touched = reactive({
  sku: false,
  name: false,
  priceAmount: false,
  priceCurrency: false,
  specifications: false,
  images: false,
});

/**
 * Re-seeds the form whenever the modal is (re)opened, rather than once on
 * mount: the same modal instance is reused for every row's edit, so a stale
 * previous row's values must never leak into the next open.
 */
watch(
  () => [props.open, props.product] as const,
  ([open, product]) => {
    if (!open) {
      return;
    }
    Object.assign(form, product ? formFromProduct(product) : blankForm());
    touched.sku = false;
    touched.name = false;
    touched.priceAmount = false;
    touched.priceCurrency = false;
    touched.specifications = false;
    touched.images = false;
    mutations.resetCreateState();
    mutations.resetUpdateState();
  },
  { immediate: true },
);

const CURRENCY_SHAPE = /^[A-Z]{3}$/;

const skuError = computed<string | null>(() => {
  if (isEditing.value) return null;
  return form.sku.trim().length === 0 ? 'SKU is required.' : null;
});
const nameError = computed<string | null>(() =>
  form.name.trim().length === 0 ? 'Name is required.' : null,
);
const priceAmountError = computed<string | null>(() => {
  // `form.priceAmount` is typed `string`, but a `type="number"` input's
  // v-model can hand back a number in some browsers/versions depending on
  // how the value was set (e.g. jsdom in tests) - coerce defensively rather
  // than assume the DOM always gives us the string it's typed as.
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
 * Same JSON-object-textarea treatment as `NewProductView.vue` — see that
 * component's comment for why this stays a raw textarea instead of a
 * dynamic key/value editor.
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
    specificationsError.value === null &&
    imagesError.value === null,
);

function addImageRow(): void {
  touched.images = true;
  form.images.push(blankImageRow(form.images.length));
}

function removeImageRow(index: number): void {
  touched.images = true;
  form.images.splice(index, 1);
}

const submitting = computed<boolean>(() => {
  const state = isEditing.value ? mutations.updateState.value : mutations.createState.value;
  return state.status === 'submitting';
});

const serverErrorMessage = computed<string | null>(() => {
  const state = isEditing.value ? mutations.updateState.value : mutations.createState.value;
  return state.status === 'error' ? state.message : null;
});
const serverErrorCorrelationId = computed<string | null>(() => {
  const state = isEditing.value ? mutations.updateState.value : mutations.createState.value;
  return state.status === 'error' ? state.correlationId : null;
});
// "Close and reload" is only the right affordance for edit's stale-version
// conflict - reloading fetches the current version so the next attempt can
// succeed. Create's duplicate-sku 409 needs a different sku typed in, not a
// reload, so this stays false there even though it's also a 409.
const serverErrorIsConflict = computed<boolean>(() => {
  if (!isEditing.value) return false;
  const state = mutations.updateState.value;
  return state.status === 'error' && state.conflict;
});

async function submit(): Promise<void> {
  touched.sku = true;
  touched.name = true;
  touched.priceAmount = true;
  touched.priceCurrency = true;
  touched.specifications = true;
  if (!formValid.value || submitting.value) {
    return;
  }

  const priceAmount = Number(form.priceAmount);
  const priceCurrency = form.priceCurrency.trim().toUpperCase();
  const sellerName = form.sellerName.trim();
  const specifications =
    form.specifications.trim().length === 0
      ? undefined
      : (JSON.parse(form.specifications.trim()) as Record<string, unknown>);
  // `images` is a full-replace field on `PUT`. The row this modal edits comes
  // from the *paged* products list, whose `images` is always `[]` (the list
  // endpoint deliberately skips the join to avoid N+1) — so if the user never
  // touched the images section, `form.images` reflects that blind spot, not
  // reality, and sending `[]` here would silently wipe any images the product
  // actually has. Only send `images` once the user has actually edited it.
  const images: readonly ProductImageRequest[] | undefined = touched.images
    ? form.images.map((row) => ({ url: row.url.trim(), displayOrder: Number(row.displayOrder) }))
    : undefined;

  if (isEditing.value && props.product) {
    const result = await mutations.updateProduct(props.product.id, {
      name: form.name.trim(),
      priceAmount,
      priceCurrency,
      active: form.active,
      version: props.product.version,
      sellerName: sellerName.length === 0 ? undefined : sellerName,
      specifications,
      images,
    });
    if (result) {
      emit('saved', result);
    }
    return;
  }

  const result = await mutations.createProduct({
    sku: form.sku.trim(),
    name: form.name.trim(),
    priceAmount,
    priceCurrency,
    active: form.active,
    sellerName: sellerName.length === 0 ? undefined : sellerName,
    specifications,
    images,
  });
  if (result) {
    emit('saved', result);
  }
}

function close(): void {
  if (submitting.value) {
    return;
  }
  emit('close');
}
</script>

<template>
  <div
    v-if="open"
    class="modal modal-open"
    role="dialog"
    aria-modal="true"
    data-test="product-form-modal"
  >
    <div class="modal-box data-card-elevated" :aria-label="isEditing ? 'Edit product' : 'New product'">
      <h3 class="text-lg font-semibold">{{ isEditing ? 'Edit product' : 'New product' }}</h3>

      <div v-if="serverErrorMessage" data-test="product-form-error">
        <ProblemAlert
          class="mt-3"
          :message="serverErrorMessage"
          :correlation-id="serverErrorCorrelationId"
        />
      </div>
      <button
        v-if="serverErrorIsConflict"
        class="btn btn-outline btn-sm mt-2"
        type="button"
        data-test="product-form-reload"
        @click="close"
      >
        Close and reload
      </button>

      <form class="mt-3 flex flex-col gap-3" novalidate @submit.prevent="submit">
        <div v-if="!isEditing" class="form-control">
          <label class="label" for="product-sku">
            <span class="label-text">SKU</span>
          </label>
          <input
            id="product-sku"
            v-model="form.sku"
            class="input input-bordered w-full"
            type="text"
            data-test="product-sku"
            :aria-invalid="touched.sku && skuError !== null"
            :aria-describedby="touched.sku && skuError ? 'product-sku-error' : undefined"
            @blur="touched.sku = true"
          />
          <p
            v-if="touched.sku && skuError"
            id="product-sku-error"
            class="mt-1 text-sm text-error"
            data-test="product-sku-error"
          >
            {{ skuError }}
          </p>
        </div>
        <div v-else class="form-control">
          <label class="label" for="product-sku-locked">
            <span class="label-text">SKU (immutable)</span>
          </label>
          <input
            id="product-sku-locked"
            class="input input-bordered w-full"
            type="text"
            disabled
            data-test="product-sku-locked"
            :value="product?.sku"
          />
        </div>

        <div class="form-control">
          <label class="label" for="product-name">
            <span class="label-text">Name</span>
          </label>
          <input
            id="product-name"
            v-model="form.name"
            class="input input-bordered w-full"
            type="text"
            data-test="product-name"
            :aria-invalid="touched.name && nameError !== null"
            :aria-describedby="touched.name && nameError ? 'product-name-error' : undefined"
            @blur="touched.name = true"
          />
          <p
            v-if="touched.name && nameError"
            id="product-name-error"
            class="mt-1 text-sm text-error"
            data-test="product-name-error"
          >
            {{ nameError }}
          </p>
        </div>

        <div class="flex gap-3">
          <div class="form-control flex-1">
            <label class="label" for="product-price-amount">
              <span class="label-text">Price amount</span>
            </label>
            <input
              id="product-price-amount"
              v-model="form.priceAmount"
              class="input input-bordered w-full"
              type="number"
              min="0"
              step="0.01"
              data-test="product-price-amount"
              :aria-invalid="touched.priceAmount && priceAmountError !== null"
              :aria-describedby="
                touched.priceAmount && priceAmountError ? 'product-price-amount-error' : undefined
              "
              @blur="touched.priceAmount = true"
            />
            <p
              v-if="touched.priceAmount && priceAmountError"
              id="product-price-amount-error"
              class="mt-1 text-sm text-error"
              data-test="product-price-amount-error"
            >
              {{ priceAmountError }}
            </p>
          </div>

          <div class="form-control w-28">
            <label class="label" for="product-price-currency">
              <span class="label-text">Currency</span>
            </label>
            <input
              id="product-price-currency"
              v-model="form.priceCurrency"
              class="input input-bordered w-full uppercase"
              type="text"
              maxlength="3"
              data-test="product-price-currency"
              :aria-invalid="touched.priceCurrency && priceCurrencyError !== null"
              :aria-describedby="
                touched.priceCurrency && priceCurrencyError
                  ? 'product-price-currency-error'
                  : undefined
              "
              @blur="touched.priceCurrency = true"
            />
            <p
              v-if="touched.priceCurrency && priceCurrencyError"
              id="product-price-currency-error"
              class="mt-1 text-sm text-error"
              data-test="product-price-currency-error"
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
              data-test="product-active"
            />
            <span class="label-text">Active</span>
          </label>
          <!-- Advisory only - see the `Product.lastKnownQuantity` doc comment
               in `types/api.ts`. Never treated as authoritative stock here. -->
          <p
            v-if="isEditing && product?.lastKnownQuantity != null"
            class="mt-1 text-sm opacity-70"
            data-test="product-form-last-known-quantity"
          >
            Last known stock: {{ product.lastKnownQuantity }} (advisory - see Inventory for the current count)
          </p>
        </div>

        <div class="form-control">
          <label class="label" for="product-seller-name">
            <span class="label-text">Seller name <span class="opacity-60">(optional)</span></span>
          </label>
          <input
            id="product-seller-name"
            v-model="form.sellerName"
            class="input input-bordered w-full"
            type="text"
            data-test="product-seller-name"
          />
        </div>

        <div class="form-control">
          <label class="label" for="product-specifications">
            <span class="label-text">Specifications <span class="opacity-60">(optional JSON object)</span></span>
          </label>
          <textarea
            id="product-specifications"
            v-model="form.specifications"
            class="textarea textarea-bordered w-full font-mono text-sm"
            rows="4"
            placeholder='{"color": "red", "material": "aluminum"}'
            data-test="product-specifications"
            :aria-invalid="touched.specifications && specificationsError !== null"
            :aria-describedby="
              touched.specifications && specificationsError ? 'product-specifications-error' : undefined
            "
            @blur="touched.specifications = true"
          />
          <p
            v-if="touched.specifications && specificationsError"
            id="product-specifications-error"
            class="mt-1 text-sm text-error"
            data-test="product-specifications-error"
          >
            {{ specificationsError }}
          </p>
        </div>

        <div class="form-control">
          <div class="flex items-baseline justify-between">
            <span class="label-text">Images <span class="opacity-60">(optional)</span></span>
            <button
              class="btn btn-outline btn-xs"
              type="button"
              data-test="product-image-add"
              @click="addImageRow"
            >
              Add image
            </button>
          </div>
          <p v-if="isEditing" class="mt-1 text-xs opacity-60">
            Editing images here replaces the product's entire image list on save.
          </p>
          <div
            v-for="(row, index) in form.images"
            :key="index"
            class="mt-2 flex items-start gap-2"
            data-test="product-image-row"
          >
            <input
              v-model="row.url"
              class="input input-bordered input-sm flex-1"
              type="url"
              :aria-label="`Image ${index + 1} URL`"
              placeholder="https://…"
              :data-test="`product-image-url-${index}`"
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
              :data-test="`product-image-order-${index}`"
              @blur="touched.images = true"
            />
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              :data-test="`product-image-remove-${index}`"
              @click="removeImageRow(index)"
            >
              Remove
            </button>
          </div>
          <p v-if="touched.images && imagesError" class="mt-1 text-sm text-error" data-test="product-images-error">
            {{ imagesError }}
          </p>
        </div>

        <div class="modal-action">
          <button
            class="btn btn-ghost"
            type="button"
            data-test="product-form-cancel"
            :disabled="submitting"
            @click="close"
          >
            Cancel
          </button>
          <button
            class="btn btn-primary"
            type="submit"
            data-test="product-form-submit"
            :disabled="submitting"
          >
            <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
            {{ isEditing ? 'Save changes' : 'Create product' }}
          </button>
        </div>
      </form>
    </div>
    <button
      class="modal-backdrop"
      type="button"
      aria-label="Close dialog"
      data-test="product-form-backdrop"
      @click="close"
    />
  </div>
</template>
