import { shallowRef, type ShallowRef } from 'vue';

import { apiFetch } from '@/lib/http';
import { ApiError } from '@/lib/problem';
import type { CreateProductRequest, Product, UpdateProductRequest } from '@/types/api';

/**
 * Submission state for a single mutation as a discriminated union, mirroring
 * `useProducts.ts`'s `ProductsState` philosophy: `idle | submitting | error | success`
 * are mutually exclusive, so a form can never render a spinner over a stale error.
 *
 * `error` carries a `conflict` flag rather than a separate status: a 409 is still
 * "the request failed, let the user retry", just with different copy and a
 * caller-driven reload instead of a plain resubmit — collapsing it into `error`
 * would lose that distinction, and giving it its own top-level status would force
 * every consumer to handle a fifth branch that behaves like the fourth.
 */
export type MutationState =
  | { readonly status: 'idle' }
  | { readonly status: 'submitting' }
  | {
      readonly status: 'error';
      readonly message: string;
      readonly correlationId: string | null;
      readonly conflict: boolean;
    }
  | { readonly status: 'success' };

export interface UseProductMutations {
  readonly createState: ShallowRef<MutationState>;
  readonly updateState: ShallowRef<MutationState>;
  readonly deleteState: ShallowRef<MutationState>;
  createProduct(body: CreateProductRequest): Promise<Product | null>;
  updateProduct(id: string, body: UpdateProductRequest): Promise<Product | null>;
  deleteProduct(id: string): Promise<boolean>;
  resetCreateState(): void;
  resetUpdateState(): void;
}

function errorStateFrom(error: unknown, fallback: string): MutationState {
  const apiError = error instanceof ApiError ? error : null;
  return {
    status: 'error',
    message: apiError?.message ?? fallback,
    correlationId: apiError?.correlationId ?? null,
    conflict: apiError?.status === 409,
  };
}

/**
 * Create/update/delete against `/api/products`, each with its own state so a
 * form driving `createState` never flickers because an unrelated delete is
 * also in flight.
 */
export function useProductMutations(): UseProductMutations {
  const createState = shallowRef<MutationState>({ status: 'idle' });
  const updateState = shallowRef<MutationState>({ status: 'idle' });
  const deleteState = shallowRef<MutationState>({ status: 'idle' });

  async function createProduct(body: CreateProductRequest): Promise<Product | null> {
    createState.value = { status: 'submitting' };
    try {
      const product = await apiFetch<Product>('/api/products', { method: 'POST', body });
      createState.value = { status: 'success' };
      return product;
    } catch (error: unknown) {
      createState.value = errorStateFrom(error, 'The product could not be created.');
      return null;
    }
  }

  async function updateProduct(id: string, body: UpdateProductRequest): Promise<Product | null> {
    updateState.value = { status: 'submitting' };
    try {
      const product = await apiFetch<Product>(`/api/products/${id}`, { method: 'PUT', body });
      updateState.value = { status: 'success' };
      return product;
    } catch (error: unknown) {
      updateState.value = errorStateFrom(error, 'The product could not be updated.');
      return null;
    }
  }

  async function deleteProduct(id: string): Promise<boolean> {
    deleteState.value = { status: 'submitting' };
    try {
      await apiFetch<void>(`/api/products/${id}`, { method: 'DELETE' });
      deleteState.value = { status: 'success' };
      return true;
    } catch (error: unknown) {
      deleteState.value = errorStateFrom(error, 'The product could not be deleted.');
      return false;
    }
  }

  return {
    createState,
    updateState,
    deleteState,
    createProduct,
    updateProduct,
    deleteProduct,
    resetCreateState: () => {
      createState.value = { status: 'idle' };
    },
    resetUpdateState: () => {
      updateState.value = { status: 'idle' };
    },
  };
}
