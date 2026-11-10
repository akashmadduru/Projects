<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import ProductFormModal from '@/components/ProductFormModal.vue';
import { useProductMutations } from '@/composables/useProductMutations';
import { useProducts } from '@/composables/useProducts';
import { useAuthStore } from '@/stores/auth';
import type { Product } from '@/types/api';

/**
 * The catalog screen. All data concerns live in `useProducts`; this component
 * only decides how each state looks, which is what keeps it testable without a
 * network and re-usable if the catalog is ever embedded elsewhere.
 *
 * Create lives on its own page (`NewProductView.vue`, routed via `new-product`)
 * rather than in a modal here - editing stays a modal (`ProductFormModal.vue`),
 * since that flow benefits from staying anchored to the row it's changing.
 */
const catalog = useProducts();
const auth = useAuthStore();
const mutations = useProductMutations();

const formModalOpen = ref(false);
const editingProduct = ref<Product | null>(null);

const deleteDialogOpen = ref(false);
const deletingProduct = ref<Product | null>(null);
const deleteSubmitting = computed<boolean>(() => mutations.deleteState.value.status === 'submitting');
const deleteErrorMessage = computed<string | null>(() => {
  const state = mutations.deleteState.value;
  return state.status === 'error' ? state.message : null;
});
const deleteErrorCorrelationId = computed<string | null>(() => {
  const state = mutations.deleteState.value;
  return state.status === 'error' ? state.correlationId : null;
});

function openEditModal(product: Product): void {
  editingProduct.value = product;
  formModalOpen.value = true;
}

function closeFormModal(): void {
  formModalOpen.value = false;
  editingProduct.value = null;
}

function onProductSaved(): void {
  closeFormModal();
  void catalog.reload();
}

function openDeleteDialog(product: Product): void {
  deletingProduct.value = product;
  deleteDialogOpen.value = true;
}

function closeDeleteDialog(): void {
  deleteDialogOpen.value = false;
  deletingProduct.value = null;
}

async function confirmDelete(): Promise<void> {
  const target = deletingProduct.value;
  if (!target) {
    return;
  }
  const succeeded = await mutations.deleteProduct(target.id);
  if (succeeded) {
    closeDeleteDialog();
    void catalog.reload();
  }
}

/**
 * The union is unwrapped here rather than narrowed inside the template: the
 * discrimination is expressed once, in TypeScript, and the template consumes flat
 * values. It also keeps branch-specific data out of the template's reach in the
 * branches where it does not exist.
 */
const status = computed(() => catalog.state.value.status);
const products = computed<readonly Product[]>(() =>
  catalog.state.value.status === 'success' ? catalog.state.value.products : [],
);
const errorMessage = computed<string>(() =>
  catalog.state.value.status === 'error' ? catalog.state.value.message : '',
);
const errorCorrelationId = computed<string | null>(() =>
  catalog.state.value.status === 'error' ? catalog.state.value.correlationId : null,
);

const pageNumber = computed<number>(() => catalog.page.value);
const pageCount = computed<number>(() => Math.max(catalog.totalPages.value, 1));

onMounted(() => {
  void catalog.load(0);
});

/**
 * `priceAmount` arrives as a JSON number (Jackson's BigDecimal default) but is
 * accepted as a string too, so formatting normalises both. Intl is given the
 * currency the server sent — a hard-coded '$' would be wrong the first time the
 * catalog carries a second currency, which the schema already allows.
 */
function formatPrice(product: Product): string {
  const amount =
    typeof product.priceAmount === 'number' ? product.priceAmount : Number(product.priceAmount);
  if (!Number.isFinite(amount)) {
    return `${String(product.priceAmount)} ${product.priceCurrency}`;
  }
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: product.priceCurrency,
    }).format(amount);
  } catch {
    // Intl throws on an unknown currency code rather than degrading.
    return `${amount.toFixed(2)} ${product.priceCurrency}`;
  }
}
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Products</h1>
      <div class="flex items-center gap-4">
        <span v-if="status === 'success'" class="text-sm opacity-70">
          {{ catalog.totalElements.value }} total
        </span>
        <RouterLink
          v-if="auth.canManageCatalog"
          class="btn btn-primary btn-sm"
          :to="{ name: 'new-product' }"
          data-test="new-product"
        >
          New Product
        </RouterLink>
      </div>
    </div>

    <!-- loading -->
    <div
      v-if="status === 'loading'"
      class="data-card p-8 text-center"
      data-test="state-loading"
      role="status"
      aria-live="polite"
    >
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading products…</p>
    </div>

    <!-- error -->
    <div v-else-if="status === 'error'" data-test="state-error">
      <ProblemAlert :message="errorMessage" :correlation-id="errorCorrelationId" />
      <button class="btn btn-outline mt-4" type="button" data-test="retry" @click="catalog.reload()">
        Try again
      </button>
    </div>

    <!-- empty -->
    <div
      v-else-if="status === 'empty'"
      class="data-card p-10 text-center"
      data-test="state-empty"
    >
      <h2 class="text-lg font-medium">No products yet</h2>
      <p class="mt-1 opacity-70">
        The catalog is empty. Products are loaded by the catalog service.
      </p>
    </div>

    <!-- success -->
    <div v-else class="data-card p-0" data-test="state-success">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Product catalog, page {{ pageNumber + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col">SKU</th>
              <th scope="col">Name</th>
              <th scope="col">Seller</th>
              <th scope="col" class="text-right">Price</th>
              <th scope="col" class="text-right">Last known stock</th>
              <th scope="col">Status</th>
              <th v-if="auth.canManageCatalog" scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="product in products" :key="product.id" data-test="product-row">
              <td class="font-mono text-sm">{{ product.sku }}</td>
              <td>{{ product.name }}</td>
              <td class="text-sm opacity-80">{{ product.sellerName ?? '—' }}</td>
              <td class="text-right tabular-nums">{{ formatPrice(product) }}</td>
              <td class="text-right tabular-nums text-sm opacity-70">
                {{ product.lastKnownQuantity ?? '—' }}
              </td>
              <td>
                <span class="inline-flex items-center gap-2">
                  <span
                    class="status-dot"
                    :class="product.active ? 'status-dot-success' : 'status-dot-neutral'"
                    aria-hidden="true"
                  />
                  {{ product.active ? 'Active' : 'Inactive' }}
                </span>
              </td>
              <td v-if="auth.canManageCatalog" class="text-right">
                <div class="flex justify-end gap-2">
                  <button
                    class="btn btn-outline btn-xs"
                    type="button"
                    data-test="edit-product"
                    @click="openEditModal(product)"
                  >
                    Edit
                  </button>
                  <button
                    class="btn btn-error btn-outline btn-xs"
                    type="button"
                    data-test="delete-product"
                    @click="openDeleteDialog(product)"
                  >
                    Delete
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="flex items-center justify-between gap-4 border-t border-base-content/10 p-4">
        <span class="text-sm opacity-70" data-test="page-indicator">
          Page {{ pageNumber + 1 }} of {{ pageCount }}
        </span>
        <div class="join">
          <button
            class="btn btn-sm join-item"
            type="button"
            data-test="previous-page"
            :disabled="!catalog.hasPrevious.value"
            @click="catalog.previousPage()"
          >
            Previous
          </button>
          <button
            class="btn btn-sm join-item"
            type="button"
            data-test="next-page"
            :disabled="!catalog.hasNext.value"
            @click="catalog.nextPage()"
          >
            Next
          </button>
        </div>
      </div>
    </div>

    <ProductFormModal
      :open="formModalOpen"
      :product="editingProduct"
      @saved="onProductSaved"
      @close="closeFormModal"
    />

    <div v-if="deleteErrorMessage" class="mt-4">
      <ProblemAlert :message="deleteErrorMessage" :correlation-id="deleteErrorCorrelationId" />
    </div>

    <ConfirmDialog
      :open="deleteDialogOpen"
      title="Delete product"
      :message="
        deletingProduct
          ? `Delete '${deletingProduct.name}' (${deletingProduct.sku})? This cannot be undone.`
          : ''
      "
      confirm-label="Delete"
      :submitting="deleteSubmitting"
      @confirm="confirmDelete"
      @cancel="closeDeleteDialog"
    />
  </section>
</template>
