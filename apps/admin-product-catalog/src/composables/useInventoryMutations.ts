import { shallowRef, type ShallowRef } from 'vue';

import { apiFetch } from '@/lib/http';
import { ApiError } from '@/lib/problem';
import type { CreateInventoryRequest, Inventory, UpdateInventoryRequest } from '@/types/api';

/**
 * Submission state for a single mutation, mirroring `useProductMutations.ts`'s
 * `MutationState` shape exactly — `conflict` on `error` distinguishes a stale-
 * version/duplicate-productId `409` (needs a reload or a different product, not
 * a plain resubmit) from every other failure.
 */
export type InventoryMutationState =
  | { readonly status: 'idle' }
  | { readonly status: 'submitting' }
  | {
      readonly status: 'error';
      readonly message: string;
      readonly correlationId: string | null;
      readonly conflict: boolean;
    }
  | { readonly status: 'success' };

export interface UseInventoryMutations {
  readonly createState: ShallowRef<InventoryMutationState>;
  readonly updateState: ShallowRef<InventoryMutationState>;
  readonly deleteState: ShallowRef<InventoryMutationState>;
  createInventory(body: CreateInventoryRequest): Promise<Inventory | null>;
  updateQuantity(id: string, body: UpdateInventoryRequest): Promise<Inventory | null>;
  deleteInventory(id: string): Promise<boolean>;
  resetCreateState(): void;
  resetUpdateState(): void;
}

function errorStateFrom(error: unknown, fallback: string): InventoryMutationState {
  const apiError = error instanceof ApiError ? error : null;
  return {
    status: 'error',
    message: apiError?.message ?? fallback,
    correlationId: apiError?.correlationId ?? null,
    conflict: apiError?.status === 409,
  };
}

/**
 * Create/update/delete against `/api/inventory`, each with its own state so a
 * form driving `createState` never flickers because an unrelated row's inline
 * quantity edit is also in flight.
 */
export function useInventoryMutations(): UseInventoryMutations {
  const createState = shallowRef<InventoryMutationState>({ status: 'idle' });
  const updateState = shallowRef<InventoryMutationState>({ status: 'idle' });
  const deleteState = shallowRef<InventoryMutationState>({ status: 'idle' });

  async function createInventory(body: CreateInventoryRequest): Promise<Inventory | null> {
    createState.value = { status: 'submitting' };
    try {
      const row = await apiFetch<Inventory>('/api/inventory', { method: 'POST', body });
      createState.value = { status: 'success' };
      return row;
    } catch (error: unknown) {
      createState.value = errorStateFrom(error, 'The stock entry could not be created.');
      return null;
    }
  }

  async function updateQuantity(
    id: string,
    body: UpdateInventoryRequest,
  ): Promise<Inventory | null> {
    updateState.value = { status: 'submitting' };
    try {
      const row = await apiFetch<Inventory>(`/api/inventory/${id}`, { method: 'PUT', body });
      updateState.value = { status: 'success' };
      return row;
    } catch (error: unknown) {
      updateState.value = errorStateFrom(error, 'The quantity could not be updated.');
      return null;
    }
  }

  async function deleteInventory(id: string): Promise<boolean> {
    deleteState.value = { status: 'submitting' };
    try {
      await apiFetch<void>(`/api/inventory/${id}`, { method: 'DELETE' });
      deleteState.value = { status: 'success' };
      return true;
    } catch (error: unknown) {
      deleteState.value = errorStateFrom(error, 'The stock entry could not be deleted.');
      return false;
    }
  }

  return {
    createState,
    updateState,
    deleteState,
    createInventory,
    updateQuantity,
    deleteInventory,
    resetCreateState: () => {
      createState.value = { status: 'idle' };
    },
    resetUpdateState: () => {
      updateState.value = { status: 'idle' };
    },
  };
}
