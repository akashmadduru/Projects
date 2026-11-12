<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { useProductMutations } from '@/composables/useProductMutations';
import { getProduct, isCategoryActive, listCategories, replaceVariants } from '@/lib/adminApi';
import { ApiError } from '@/lib/problem';
import type { Product, ProductImageRequest, ProductVariantRequest } from '@/types/api';
import type { Category } from '@/types/commerce';

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

/** Phase 7 attribute row (filterable facet key/value). */
interface AttrRow {
  key: string;
  value: string;
}

/** Phase 7 variant row. `attrs` is edited as "key=value, key=value". */
interface VariantRow {
  id?: string;
  sku: string;
  attrs: string;
  priceAmount: string;
  salePrice: string;
  imageUrl: string;
  active: boolean;
  sortOrder: string;
}

function blankVariantRow(nextOrder: number): VariantRow {
  return { sku: '', attrs: '', priceAmount: '', salePrice: '', imageUrl: '', active: true, sortOrder: String(nextOrder) };
}

function attrsToText(attrs: Readonly<Record<string, string>>): string {
  return Object.entries(attrs)
    .map(([k, v]) => `${k}=${v}`)
    .join(', ');
}

/** Returns null when the text is malformed. Empty text is a valid empty map. */
function parseAttrs(text: string): Record<string, string> | null {
  const out: Record<string, string> = {};
  const raw = text.trim();
  if (raw.length === 0) return out;
  for (const part of raw.split(',')) {
    const idx = part.indexOf('=');
    if (idx <= 0) return null;
    const k = part.slice(0, idx).trim();
    const v = part.slice(idx + 1).trim();
    if (k.length === 0 || v.length === 0) return null;
    out[k] = v;
  }
  return out;
}

interface FormState {
  brand: string;
  attributes: AttrRow[];
  variants: VariantRow[];
  sku: string;
  name: string;
  priceAmount: string;
  priceCurrency: string;
  active: boolean;
  sellerName: string;
  /** '' = no category. PUT is full-replace for this field, so it must always be sent. */
  categoryId: string;
  specifications: string;
  images: ImageRow[];
}

function blankForm(): FormState {
  return {
    brand: '',
    attributes: [],
    variants: [],
    sku: '',
    name: '',
    priceAmount: '',
    priceCurrency: 'USD',
    active: true,
    sellerName: '',
    categoryId: '',
    specifications: '',
    images: [],
  };
}

function formFromProduct(product: Product): FormState {
  return {
    brand: product.brand ?? '',
    attributes: Object.entries(product.attributes ?? {}).map(([key, value]) => ({ key, value: String(value) })),
    variants: (product.variants ?? []).map((v) => ({
      id: v.id,
      sku: v.sku,
      attrs: attrsToText(v.attributes ?? {}),
      priceAmount: v.priceAmount === null || v.priceAmount === undefined ? '' : String(v.priceAmount),
      salePrice: v.salePrice === null || v.salePrice === undefined ? '' : String(v.salePrice),
      imageUrl: v.imageUrl ?? '',
      active: v.active,
      sortOrder: String(v.sortOrder ?? 0),
    })),
    sku: product.sku,
    name: product.name,
    priceAmount: String(product.priceAmount),
    priceCurrency: product.priceCurrency,
    active: product.active,
    sellerName: product.sellerName ?? '',
    categoryId: product.categoryId ?? '',
    specifications: product.specifications ? JSON.stringify(product.specifications, null, 2) : '',
    images: product.images.map((image) => ({ url: image.url, displayOrder: String(image.displayOrder) })),
  };
}

const form = reactive<FormState>(blankForm());

/**
 * Category options, loaded once per modal instance. A failed load leaves the
 * list empty but keeps the product's current categoryId selected (shown as an
 * "unknown" option) so a save never silently clears it.
 */
const categories = ref<readonly Category[]>([]);
const categoriesLoaded = ref(false);
async function loadCategories(): Promise<void> {
  if (categoriesLoaded.value) return;
  try {
    categories.value = (await listCategories()).content;
    categoriesLoaded.value = true;
  } catch {
    categories.value = [];
  }
}
const categoryOptions = computed(() => {
  const byId = new Map(categories.value.map((c) => [c.id, c]));
  const opts = [...categories.value]
    .filter((c) => isCategoryActive(c) || c.id === form.categoryId)
    .map((c) => {
      const parent = c.parentCategoryId ? byId.get(c.parentCategoryId) : undefined;
      return { id: c.id, label: parent ? `${parent.name} › ${c.name}` : c.name };
    })
    .sort((a, b) => a.label.localeCompare(b.label));
  if (form.categoryId && !byId.has(form.categoryId)) {
    opts.unshift({ id: form.categoryId, label: `Current (${form.categoryId.slice(0, 8)})` });
  }
  return opts;
});
watch(
  () => props.open,
  (open) => {
    if (open) void loadCategories();
  },
  { immediate: true },
);

const touched = reactive({
  sku: false,
  name: false,
  priceAmount: false,
  priceCurrency: false,
  specifications: false,
  images: false,
  attributes: false,
  variants: false,
});

/**
 * The paged list row carries no variants (and may omit brand/attributes), so on edit the full
 * product is fetched. Brand/attributes/variants are only sent once that detail load succeeded
 * (or the user edited them) - otherwise a full-replace PUT would wipe data we never saw.
 */
const detailLoaded = ref(false);
const detailLoading = ref(false);
const variantsError = ref<string | null>(null);
/** Create succeeded but the follow-up variants save failed; retry only re-saves variants. */
const createdProduct = ref<Product | null>(null);

async function loadDetail(id: string): Promise<void> {
  detailLoaded.value = false;
  detailLoading.value = true;
  try {
    const full = await getProduct(id);
    if (props.product?.id !== id) return;
    const seeded = formFromProduct(full);
    form.brand = seeded.brand;
    form.attributes = seeded.attributes;
    form.variants = seeded.variants;
    detailLoaded.value = true;
  } catch {
    // Leave the sections empty and unsent; the rest of the form still saves.
  } finally {
    detailLoading.value = false;
  }
}

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
    touched.attributes = false;
    touched.variants = false;
    variantsError.value = null;
    createdProduct.value = null;
    detailLoaded.value = false;
    if (product) void loadDetail(product.id);
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
const attributesError = computed<string | null>(() => {
  const seen = new Set<string>();
  for (const row of form.attributes) {
    const k = row.key.trim();
    if (k.length === 0 || row.value.trim().length === 0) return 'Each attribute needs a key and a value, or remove the row.';
    if (seen.has(k.toLowerCase())) return `Duplicate attribute key "${k}".`;
    seen.add(k.toLowerCase());
  }
  return null;
});
const variantsValidation = computed<string | null>(() => {
  const skus = new Set<string>();
  for (const [i, row] of form.variants.entries()) {
    const n = i + 1;
    const sku = row.sku.trim();
    if (sku.length === 0) return `Variant ${n}: SKU is required.`;
    if (skus.has(sku.toLowerCase())) return `Variant ${n}: duplicate SKU "${sku}".`;
    skus.add(sku.toLowerCase());
    if (parseAttrs(row.attrs) === null) return `Variant ${n}: attributes must look like size=M, color=Red.`;
    for (const [label, v] of [['Price', row.priceAmount], ['Sale price', row.salePrice]] as const) {
      const t = String(v).trim();
      if (t.length > 0 && (!Number.isFinite(Number(t)) || Number(t) < 0)) {
        return `Variant ${n}: ${label} must be a non-negative number.`;
      }
    }
    const order = Number(row.sortOrder);
    if (!Number.isInteger(order) || order < 0) return `Variant ${n}: order must be a non-negative whole number.`;
  }
  return null;
});
const formValid = computed<boolean>(
  () =>
    attributesError.value === null &&
    variantsValidation.value === null &&
    skuError.value === null &&
    nameError.value === null &&
    priceAmountError.value === null &&
    priceCurrencyError.value === null &&
    specificationsError.value === null &&
    imagesError.value === null,
);

function addAttrRow(): void {
  touched.attributes = true;
  form.attributes.push({ key: '', value: '' });
}

function removeAttrRow(index: number): void {
  touched.attributes = true;
  form.attributes.splice(index, 1);
}

function addVariantRow(): void {
  touched.variants = true;
  form.variants.push(blankVariantRow(form.variants.length));
}

function removeVariantRow(index: number): void {
  touched.variants = true;
  form.variants.splice(index, 1);
}

function buildVariants(): ProductVariantRequest[] {
  const optNum = (v: string): number | null => (String(v).trim().length === 0 ? null : Number(v));
  return form.variants.map((row) => ({
    ...(row.id ? { id: row.id } : {}),
    sku: row.sku.trim(),
    attributes: parseAttrs(row.attrs) ?? {},
    priceAmount: optNum(row.priceAmount),
    salePrice: optNum(row.salePrice),
    imageUrl: row.imageUrl.trim() || null,
    active: row.active,
    sortOrder: Number(row.sortOrder),
  }));
}

/** Saves variants for a persisted product; returns false (and sets variantsError) on failure. */
async function saveVariants(productId: string): Promise<boolean> {
  variantsError.value = null;
  try {
    await replaceVariants(productId, buildVariants());
    return true;
  } catch (e: unknown) {
    const msg = e instanceof ApiError ? e.message : 'The variants could not be saved.';
    variantsError.value = `Product saved, but variants failed: ${msg}`;
    return false;
  }
}

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
  if (createdProduct.value) {
    // Retry path: the product exists already, only the variants save failed.
    if (variantsValidation.value === null && (await saveVariants(createdProduct.value.id))) {
      emit('saved', createdProduct.value);
    }
    return;
  }
  if (!formValid.value || submitting.value) {
    return;
  }
  const sendExtras = !isEditing.value || detailLoaded.value || touched.attributes;
  const brand = form.brand.trim();
  const attributes: Record<string, string> | undefined = sendExtras
    ? Object.fromEntries(form.attributes.map((r) => [r.key.trim(), r.value.trim()]))
    : undefined;
  const brandField = sendExtras ? (brand.length === 0 ? null : brand) : undefined;
  const saveVariantsToo = form.variants.length > 0 || (isEditing.value && (detailLoaded.value || touched.variants));

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
      categoryId: form.categoryId || null,
      specifications,
      images,
      brand: brandField,
      attributes,
    });
    if (result) {
      if (saveVariantsToo && !(await saveVariants(result.id))) {
        createdProduct.value = result;
        return;
      }
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
    categoryId: form.categoryId || null,
    specifications,
    images,
    brand: brandField ?? undefined,
    attributes: attributes && Object.keys(attributes).length > 0 ? attributes : undefined,
  });
  if (result) {
    if (saveVariantsToo && !(await saveVariants(result.id))) {
      createdProduct.value = result;
      return;
    }
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
      <ProblemAlert v-if="variantsError" class="mt-3" :message="variantsError" :correlation-id="null" />
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
          <label class="label" for="product-brand">
            <span class="label-text">Brand <span class="opacity-60">(optional)</span></span>
          </label>
          <input
            id="product-brand"
            v-model="form.brand"
            class="input input-bordered w-full"
            type="text"
            maxlength="120"
            data-test="product-brand"
          />
        </div>

        <div class="form-control">
          <label class="label" for="product-category">
            <span class="label-text">Category <span class="opacity-60">(optional)</span></span>
          </label>
          <select
            id="product-category"
            v-model="form.categoryId"
            class="select select-bordered w-full"
            data-test="product-category"
          >
            <option value="">No category</option>
            <option v-for="c in categoryOptions" :key="c.id" :value="c.id">{{ c.label }}</option>
          </select>
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

        <div class="form-control">
          <div class="flex items-baseline justify-between">
            <span class="label-text">Attributes <span class="opacity-60">(filterable, e.g. RAM = 8 GB)</span></span>
            <button class="btn btn-outline btn-xs" type="button" data-test="product-attr-add" @click="addAttrRow">
              Add attribute
            </button>
          </div>
          <div v-for="(row, index) in form.attributes" :key="index" class="mt-2 flex items-start gap-2">
            <input
              v-model="row.key"
              class="input input-bordered input-sm w-1/3"
              type="text"
              :aria-label="`Attribute ${index + 1} key`"
              placeholder="Key"
              @input="touched.attributes = true"
            />
            <input
              v-model="row.value"
              class="input input-bordered input-sm flex-1"
              type="text"
              :aria-label="`Attribute ${index + 1} value`"
              placeholder="Value"
              @input="touched.attributes = true"
            />
            <button class="btn btn-ghost btn-sm" type="button" @click="removeAttrRow(index)">Remove</button>
          </div>
          <p v-if="attributesError" class="mt-1 text-sm text-error" data-test="product-attributes-error">
            {{ attributesError }}
          </p>
        </div>

        <div class="form-control">
          <div class="flex items-baseline justify-between">
            <span class="label-text">Variants <span class="opacity-60">(optional)</span></span>
            <button class="btn btn-outline btn-xs" type="button" data-test="product-variant-add" @click="addVariantRow">
              Add variant
            </button>
          </div>
          <p v-if="detailLoading" class="mt-1 text-xs opacity-60">Loading current variants…</p>
          <p v-else-if="isEditing && !detailLoaded" class="mt-1 text-xs text-warning">
            Could not load current brand, attributes and variants; they will not be changed unless you edit them.
          </p>
          <p class="mt-1 text-xs opacity-60">
            Saving replaces the whole variant list. Attributes as key=value, comma separated;
            blank price = product price.
          </p>
          <div
            v-for="(row, index) in form.variants"
            :key="row.id ?? index"
            class="mt-2 grid grid-cols-6 gap-2 rounded border border-base-300 p-2"
            data-test="product-variant-row"
          >
            <input v-model="row.sku" class="input input-bordered input-sm col-span-2" placeholder="SKU" :aria-label="`Variant ${index + 1} SKU`" @input="touched.variants = true" />
            <input v-model="row.attrs" class="input input-bordered input-sm col-span-4" placeholder="size=M, color=Red" :aria-label="`Variant ${index + 1} attributes`" @input="touched.variants = true" />
            <input v-model="row.priceAmount" class="input input-bordered input-sm col-span-2" type="number" min="0" step="0.01" placeholder="Price" :aria-label="`Variant ${index + 1} price`" @input="touched.variants = true" />
            <input v-model="row.salePrice" class="input input-bordered input-sm col-span-2" type="number" min="0" step="0.01" placeholder="Sale price" :aria-label="`Variant ${index + 1} sale price`" @input="touched.variants = true" />
            <input v-model="row.sortOrder" class="input input-bordered input-sm col-span-2" type="number" min="0" step="1" placeholder="Order" :aria-label="`Variant ${index + 1} order`" @input="touched.variants = true" />
            <input v-model="row.imageUrl" class="input input-bordered input-sm col-span-3" type="url" placeholder="Image URL" :aria-label="`Variant ${index + 1} image URL`" @input="touched.variants = true" />
            <label class="col-span-2 flex items-center gap-2">
              <input v-model="row.active" type="checkbox" class="checkbox checkbox-sm" @change="touched.variants = true" />
              <span class="label-text">Active</span>
            </label>
            <button class="btn btn-ghost btn-sm col-span-1" type="button" @click="removeVariantRow(index)">Remove</button>
          </div>
          <p v-if="variantsValidation" class="mt-1 text-sm text-error" data-test="product-variants-error">
            {{ variantsValidation }}
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
