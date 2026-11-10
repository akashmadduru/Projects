<script setup lang="ts">
import { computed, onMounted, reactive, watch } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { useInventoryMutations } from '@/composables/useInventoryMutations';
import { useProducts } from '@/composables/useProducts';
import type { Inventory } from '@/types/api';

/**
 * Create-only: a manual stock entry for ops-correction cases (the normal path
 * is event-driven provisioning from product lifecycle - see
 * `useInventoryMutations.ts`'s `createInventory` doc comment). There is no
 * edit-via-modal here; quantity edits stay inline in `InventoryView.vue`.
 */
const props = defineProps<{
  open: boolean;
}>();

const emit = defineEmits<{
  saved: [row: Inventory];
  close: [];
}>();

const mutations = useInventoryMutations();

// A large single page rather than the list view's own paginated fetch - this
// dropdown needs every product to pick from, not one page of them.
const products = useProducts(100);

interface FormState {
  productId: string;
  quantity: string;
  warehouseCode: string;
  /** Blank means "use the server default (10)" — never pre-filled to '10' client-side. */
  lowStockThreshold: string;
}

function blankForm(): FormState {
  return { productId: '', quantity: '0', warehouseCode: '', lowStockThreshold: '' };
}

const form = reactive<FormState>(blankForm());
const touched = reactive({
  productId: false,
  quantity: false,
  warehouseCode: false,
  lowStockThreshold: false,
});

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return;
    }
    Object.assign(form, blankForm());
    touched.productId = false;
    touched.quantity = false;
    touched.warehouseCode = false;
    touched.lowStockThreshold = false;
    mutations.resetCreateState();
    void products.load(0);
  },
  { immediate: true },
);

const productOptions = computed(() =>
  products.state.value.status === 'success' ? products.state.value.products : [],
);
const productsLoading = computed<boolean>(() => products.state.value.status === 'loading');

const productIdError = computed<string | null>(() =>
  form.productId.length === 0 ? 'Select a product.' : null,
);
const quantityError = computed<string | null>(() => {
  const value = String(form.quantity).trim();
  if (value.length === 0) return 'Quantity is required.';
  const amount = Number(value);
  if (!Number.isInteger(amount) || amount < 0) return 'Quantity must be a non-negative whole number.';
  return null;
});
const warehouseCodeError = computed<string | null>(() =>
  form.warehouseCode.length > 32 ? 'Warehouse code must be 32 characters or fewer.' : null,
);
/** Blank is valid and means "omit the field, let the server default to 10". */
const lowStockThresholdError = computed<string | null>(() => {
  const value = form.lowStockThreshold.trim();
  if (value.length === 0) return null;
  const amount = Number(value);
  if (!Number.isInteger(amount) || amount < 0) {
    return 'Low stock threshold must be a non-negative whole number.';
  }
  return null;
});
const formValid = computed<boolean>(
  () =>
    productIdError.value === null &&
    quantityError.value === null &&
    warehouseCodeError.value === null &&
    lowStockThresholdError.value === null,
);

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
  touched.productId = true;
  touched.quantity = true;
  touched.warehouseCode = true;
  touched.lowStockThreshold = true;
  if (!formValid.value || submitting.value) {
    return;
  }
  const warehouseCode = form.warehouseCode.trim();
  const lowStockThreshold = form.lowStockThreshold.trim();
  const result = await mutations.createInventory({
    productId: form.productId,
    quantity: Number(form.quantity),
    warehouseCode: warehouseCode.length === 0 ? undefined : warehouseCode,
    // Deliberately `undefined` (omit the key), never `0` - a blank field
    // means "use the server default of 10", which is not the same value as
    // an explicit 0 threshold.
    lowStockThreshold: lowStockThreshold.length === 0 ? undefined : Number(lowStockThreshold),
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

onMounted(() => {
  if (props.open) {
    void products.load(0);
  }
});
</script>

<template>
  <div
    v-if="open"
    class="modal modal-open"
    role="dialog"
    aria-modal="true"
    data-test="inventory-form-modal"
  >
    <div class="modal-box data-card-elevated" aria-label="New stock entry">
      <h3 class="text-lg font-semibold">New stock entry</h3>
      <p class="mt-1 text-sm opacity-70">
        For ops corrections only — new products get a zero-quantity row automatically.
      </p>

      <div v-if="serverErrorMessage" data-test="inventory-form-error">
        <ProblemAlert
          class="mt-3"
          :message="serverErrorMessage"
          :correlation-id="serverErrorCorrelationId"
        />
      </div>

      <form class="mt-3 flex flex-col gap-3" novalidate @submit.prevent="submit">
        <div class="form-control">
          <label class="label" for="inventory-product">
            <span class="label-text">Product</span>
          </label>
          <select
            id="inventory-product"
            v-model="form.productId"
            class="select select-bordered w-full"
            data-test="inventory-form-product"
            :disabled="productsLoading"
            :aria-invalid="touched.productId && productIdError !== null"
            @blur="touched.productId = true"
          >
            <option value="" disabled>
              {{ productsLoading ? 'Loading products…' : 'Select a product' }}
            </option>
            <option v-for="product in productOptions" :key="product.id" :value="product.id">
              {{ product.sku }} — {{ product.name }}
            </option>
          </select>
          <p
            v-if="touched.productId && productIdError"
            class="mt-1 text-sm text-error"
            data-test="inventory-form-product-error"
          >
            {{ productIdError }}
          </p>
        </div>

        <div class="form-control">
          <label class="label" for="inventory-quantity">
            <span class="label-text">Starting quantity</span>
          </label>
          <input
            id="inventory-quantity"
            v-model="form.quantity"
            class="input input-bordered w-full"
            type="number"
            min="0"
            step="1"
            data-test="inventory-form-quantity"
            :aria-invalid="touched.quantity && quantityError !== null"
            @blur="touched.quantity = true"
          />
          <p
            v-if="touched.quantity && quantityError"
            class="mt-1 text-sm text-error"
            data-test="inventory-form-quantity-error"
          >
            {{ quantityError }}
          </p>
        </div>

        <div class="form-control">
          <label class="label" for="inventory-warehouse-code">
            <span class="label-text">Warehouse code <span class="opacity-60">(optional)</span></span>
          </label>
          <input
            id="inventory-warehouse-code"
            v-model="form.warehouseCode"
            class="input input-bordered w-full"
            type="text"
            maxlength="32"
            data-test="inventory-form-warehouse-code"
            :aria-invalid="touched.warehouseCode && warehouseCodeError !== null"
            :aria-describedby="
              touched.warehouseCode && warehouseCodeError
                ? 'inventory-form-warehouse-code-error'
                : undefined
            "
            @blur="touched.warehouseCode = true"
          />
          <p
            v-if="touched.warehouseCode && warehouseCodeError"
            id="inventory-form-warehouse-code-error"
            class="mt-1 text-sm text-error"
            data-test="inventory-form-warehouse-code-error"
          >
            {{ warehouseCodeError }}
          </p>
        </div>

        <div class="form-control">
          <label class="label" for="inventory-low-stock-threshold">
            <span class="label-text"
              >Low stock threshold
              <span class="opacity-60">(optional, defaults to 10)</span></span
            >
          </label>
          <input
            id="inventory-low-stock-threshold"
            v-model="form.lowStockThreshold"
            class="input input-bordered w-full"
            type="number"
            min="0"
            step="1"
            placeholder="10"
            data-test="inventory-form-low-stock-threshold"
            :aria-invalid="touched.lowStockThreshold && lowStockThresholdError !== null"
            :aria-describedby="
              touched.lowStockThreshold && lowStockThresholdError
                ? 'inventory-form-low-stock-threshold-error'
                : undefined
            "
            @blur="touched.lowStockThreshold = true"
          />
          <p
            v-if="touched.lowStockThreshold && lowStockThresholdError"
            id="inventory-form-low-stock-threshold-error"
            class="mt-1 text-sm text-error"
            data-test="inventory-form-low-stock-threshold-error"
          >
            {{ lowStockThresholdError }}
          </p>
        </div>

        <div class="modal-action">
          <button
            class="btn btn-ghost"
            type="button"
            data-test="inventory-form-cancel"
            :disabled="submitting"
            @click="close"
          >
            Cancel
          </button>
          <button
            class="btn btn-primary"
            type="submit"
            data-test="inventory-form-submit"
            :disabled="submitting"
          >
            <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
            Create entry
          </button>
        </div>
      </form>
    </div>
    <button
      class="modal-backdrop"
      type="button"
      aria-label="Close dialog"
      data-test="inventory-form-backdrop"
      @click="close"
    />
  </div>
</template>
