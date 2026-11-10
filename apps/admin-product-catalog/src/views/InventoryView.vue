<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import InventoryFormModal from '@/components/InventoryFormModal.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import { useInventory } from '@/composables/useInventory';
import { useInventoryMutations } from '@/composables/useInventoryMutations';
import { useAuthStore } from '@/stores/auth';
import type { Inventory } from '@/types/api';

/**
 * Read + inline-quantity-edit + manual create/delete (ops-correction path —
 * see `InventoryFormModal.vue`'s doc comment; the normal path stays
 * event-driven from product lifecycle).
 */
const inventory = useInventory();
const auth = useAuthStore();
const mutations = useInventoryMutations();

const formModalOpen = ref(false);

function openCreateModal(): void {
  formModalOpen.value = true;
}

function closeFormModal(): void {
  formModalOpen.value = false;
}

function onInventorySaved(): void {
  closeFormModal();
  void inventory.reload();
}

const deleteDialogOpen = ref(false);
const deletingRow = ref<Inventory | null>(null);
const deleteSubmitting = computed<boolean>(() => mutations.deleteState.value.status === 'submitting');
const deleteErrorMessage = computed<string | null>(() => {
  const state = mutations.deleteState.value;
  return state.status === 'error' ? state.message : null;
});
const deleteErrorCorrelationId = computed<string | null>(() => {
  const state = mutations.deleteState.value;
  return state.status === 'error' ? state.correlationId : null;
});

function openDeleteDialog(row: Inventory): void {
  deletingRow.value = row;
  deleteDialogOpen.value = true;
}

function closeDeleteDialog(): void {
  deleteDialogOpen.value = false;
  deletingRow.value = null;
}

async function confirmDelete(): Promise<void> {
  const target = deletingRow.value;
  if (!target) {
    return;
  }
  const succeeded = await mutations.deleteInventory(target.id);
  if (succeeded) {
    closeDeleteDialog();
    void inventory.reload();
  }
}

const status = computed(() => inventory.state.value.status);
const rows = computed<readonly Inventory[]>(() =>
  inventory.state.value.status === 'success' ? inventory.state.value.rows : [],
);
const errorMessage = computed<string>(() =>
  inventory.state.value.status === 'error' ? inventory.state.value.message : '',
);
const errorCorrelationId = computed<string | null>(() =>
  inventory.state.value.status === 'error' ? inventory.state.value.correlationId : null,
);

const pageNumber = computed<number>(() => inventory.page.value);
const pageCount = computed<number>(() => Math.max(inventory.totalPages.value, 1));

/**
 * `null` = no row is currently in edit mode. `warehouseCode`/`lowStockThreshold`
 * are seeded from the row being edited, exactly the way `quantity` already is
 * - `PUT /api/inventory/{id}` is full-replace, so a save that doesn't carry the
 * row's existing values forward would silently clear them.
 */
const editingId = reactive<{
  id: string | null;
  quantity: string;
  warehouseCode: string;
  lowStockThreshold: string;
}>({ id: null, quantity: '', warehouseCode: '', lowStockThreshold: '' });

const updateSubmitting = computed<boolean>(() => mutations.updateState.value.status === 'submitting');
const updateErrorMessage = computed<string | null>(() => {
  const state = mutations.updateState.value;
  return state.status === 'error' ? state.message : null;
});
const updateErrorCorrelationId = computed<string | null>(() => {
  const state = mutations.updateState.value;
  return state.status === 'error' ? state.correlationId : null;
});

function startEdit(row: Inventory): void {
  editingId.id = row.id;
  editingId.quantity = String(row.quantity);
  editingId.warehouseCode = row.warehouseCode ?? '';
  editingId.lowStockThreshold = String(row.lowStockThreshold);
  mutations.resetUpdateState();
}

function cancelEdit(): void {
  editingId.id = null;
  editingId.quantity = '';
  editingId.warehouseCode = '';
  editingId.lowStockThreshold = '';
  mutations.resetUpdateState();
}

const editWarehouseCodeError = computed<string | null>(() =>
  editingId.warehouseCode.length > 32 ? 'Warehouse code must be 32 characters or fewer.' : null,
);
const editLowStockThresholdError = computed<string | null>(() => {
  const amount = Number(editingId.lowStockThreshold);
  if (!Number.isInteger(amount) || amount < 0) {
    return 'Low stock threshold must be a non-negative whole number.';
  }
  return null;
});

async function saveEdit(row: Inventory): Promise<void> {
  const quantity = Number(editingId.quantity);
  if (!Number.isFinite(quantity) || quantity < 0 || !Number.isInteger(quantity)) {
    return;
  }
  if (editWarehouseCodeError.value !== null || editLowStockThresholdError.value !== null) {
    return;
  }
  const warehouseCode = editingId.warehouseCode.trim();
  const result = await mutations.updateQuantity(row.id, {
    quantity,
    version: row.version,
    warehouseCode: warehouseCode.length === 0 ? undefined : warehouseCode,
    lowStockThreshold: Number(editingId.lowStockThreshold),
  });
  if (result) {
    cancelEdit();
    void inventory.reload();
  }
}

onMounted(() => {
  void inventory.load(0);
});

/**
 * `productName`/`productSku` are denormalised via Kafka event consumption and
 * may be `null` until that event has been processed at least once for this
 * row - fall back to the raw `productId` rather than rendering blank cells.
 */
function productLabel(row: Inventory): string {
  if (row.productName && row.productSku) {
    return `${row.productSku} — ${row.productName}`;
  }
  if (row.productSku) {
    return row.productSku;
  }
  return row.productId;
}

function isLowStock(row: Inventory): boolean {
  return row.quantity <= row.lowStockThreshold;
}
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Inventory</h1>
      <div class="flex items-center gap-3">
        <span v-if="status === 'success'" class="text-sm opacity-70">
          {{ inventory.totalElements.value }} total
        </span>
        <button
          v-if="auth.canManageCatalog"
          class="btn btn-primary btn-sm"
          type="button"
          data-test="inventory-new"
          @click="openCreateModal"
        >
          New Stock Entry
        </button>
      </div>
    </div>

    <!-- loading -->
    <div
      v-if="status === 'loading'"
      class="data-card p-8 text-center"
      data-test="inventory-state-loading"
      role="status"
      aria-live="polite"
    >
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading inventory…</p>
    </div>

    <!-- error -->
    <div v-else-if="status === 'error'" data-test="inventory-state-error">
      <ProblemAlert :message="errorMessage" :correlation-id="errorCorrelationId" />
      <button
        class="btn btn-outline mt-4"
        type="button"
        data-test="inventory-retry"
        @click="inventory.reload()"
      >
        Try again
      </button>
    </div>

    <!-- empty -->
    <div
      v-else-if="status === 'empty'"
      class="data-card p-10 text-center"
      data-test="inventory-state-empty"
    >
      <h2 class="text-lg font-medium">No inventory rows yet</h2>
      <p class="mt-1 opacity-70">
        Stock levels are provisioned automatically shortly after a product is created.
      </p>
    </div>

    <!-- success -->
    <div v-else class="data-card p-0" data-test="inventory-state-success">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Inventory, page {{ pageNumber + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col">Product</th>
              <th scope="col">Warehouse</th>
              <th scope="col" class="text-right">Quantity</th>
              <th scope="col" class="text-right">Low stock threshold</th>
              <th v-if="auth.canManageCatalog" scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id" data-test="inventory-row">
              <td class="font-mono text-sm" data-test="inventory-product-label">
                {{ productLabel(row) }}
              </td>
              <td>
                <input
                  v-if="editingId.id === row.id"
                  v-model="editingId.warehouseCode"
                  type="text"
                  maxlength="32"
                  class="input input-bordered input-sm w-32"
                  aria-label="Warehouse code"
                  data-test="inventory-warehouse-code-input"
                />
                <span v-else data-test="inventory-warehouse-code">{{ row.warehouseCode ?? '—' }}</span>
              </td>
              <td class="text-right tabular-nums">
                <div class="flex items-center justify-end gap-2">
                  <input
                    v-if="editingId.id === row.id"
                    v-model="editingId.quantity"
                    type="number"
                    min="0"
                    step="1"
                    class="input input-bordered input-sm w-24 text-right"
                    aria-label="Quantity"
                    data-test="inventory-quantity-input"
                  />
                  <span v-else data-test="inventory-quantity">{{ row.quantity }}</span>
                  <span
                    v-if="editingId.id !== row.id && isLowStock(row)"
                    class="badge badge-warning badge-sm"
                    data-test="inventory-low-stock-badge"
                  >
                    Low
                  </span>
                </div>
              </td>
              <td class="text-right tabular-nums">
                <input
                  v-if="editingId.id === row.id"
                  v-model="editingId.lowStockThreshold"
                  type="number"
                  min="0"
                  step="1"
                  class="input input-bordered input-sm w-24 text-right"
                  aria-label="Low stock threshold"
                  data-test="inventory-low-stock-threshold-input"
                />
                <span v-else data-test="inventory-low-stock-threshold">{{ row.lowStockThreshold }}</span>
              </td>
              <td v-if="auth.canManageCatalog" class="text-right">
                <div class="flex justify-end gap-2">
                  <template v-if="editingId.id === row.id">
                    <button
                      class="btn btn-primary btn-xs"
                      type="button"
                      data-test="inventory-save"
                      :disabled="updateSubmitting"
                      @click="saveEdit(row)"
                    >
                      <span
                        v-if="updateSubmitting"
                        class="loading loading-spinner loading-xs"
                        aria-hidden="true"
                      />
                      Save
                    </button>
                    <button
                      class="btn btn-ghost btn-xs"
                      type="button"
                      data-test="inventory-cancel"
                      :disabled="updateSubmitting"
                      @click="cancelEdit"
                    >
                      Cancel
                    </button>
                  </template>
                  <template v-else>
                    <button
                      class="btn btn-outline btn-xs"
                      type="button"
                      data-test="inventory-edit"
                      @click="startEdit(row)"
                    >
                      Edit
                    </button>
                    <button
                      class="btn btn-outline btn-error btn-xs"
                      type="button"
                      data-test="inventory-delete"
                      @click="openDeleteDialog(row)"
                    >
                      Delete
                    </button>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="updateErrorMessage" class="p-4 pt-0">
        <ProblemAlert :message="updateErrorMessage" :correlation-id="updateErrorCorrelationId" />
      </div>

      <div class="flex items-center justify-between gap-4 border-t border-base-content/10 p-4">
        <span class="text-sm opacity-70" data-test="inventory-page-indicator">
          Page {{ pageNumber + 1 }} of {{ pageCount }}
        </span>
        <div class="join">
          <button
            class="btn btn-sm join-item"
            type="button"
            data-test="inventory-previous-page"
            :disabled="!inventory.hasPrevious.value"
            @click="inventory.previousPage()"
          >
            Previous
          </button>
          <button
            class="btn btn-sm join-item"
            type="button"
            data-test="inventory-next-page"
            :disabled="!inventory.hasNext.value"
            @click="inventory.nextPage()"
          >
            Next
          </button>
        </div>
      </div>
    </div>

    <InventoryFormModal
      :open="formModalOpen"
      @saved="onInventorySaved"
      @close="closeFormModal"
    />

    <div v-if="deleteErrorMessage" class="mt-4">
      <ProblemAlert :message="deleteErrorMessage" :correlation-id="deleteErrorCorrelationId" />
    </div>

    <ConfirmDialog
      :open="deleteDialogOpen"
      title="Delete stock entry"
      :message="
        deletingRow
          ? `Delete the stock entry for product ${productLabel(deletingRow)}? This cannot be undone.`
          : ''
      "
      confirm-label="Delete"
      :submitting="deleteSubmitting"
      @confirm="confirmDelete"
      @cancel="closeDeleteDialog"
    />
  </section>
</template>
