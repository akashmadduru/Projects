import { computed, ref, shallowRef, type ComputedRef, type Ref, type ShallowRef } from 'vue';

import { apiFetch } from '@/lib/http';
import { ApiError } from '@/lib/problem';
import type { PagedModel, Product } from '@/types/api';

/**
 * Catalog page state as a discriminated union rather than four loose booleans.
 *
 * `loading && error` and `!loading && !error && !data` are unrepresentable, so the
 * template cannot render a spinner over an error or an empty state during the
 * first load — the classic failure of the boolean-soup approach.
 */
export type ProductsState =
  | { readonly status: 'loading' }
  | { readonly status: 'error'; readonly message: string; readonly correlationId: string | null }
  | { readonly status: 'empty' }
  | { readonly status: 'success'; readonly products: readonly Product[] };

export interface UseProducts {
  /** shallow: state objects are replaced wholesale, never mutated in place. */
  readonly state: ShallowRef<ProductsState>;
  readonly page: Ref<number>;
  readonly size: number;
  readonly totalPages: ComputedRef<number>;
  readonly totalElements: ComputedRef<number>;
  readonly hasPrevious: ComputedRef<boolean>;
  readonly hasNext: ComputedRef<boolean>;
  load(pageNumber?: number): Promise<void>;
  goToPage(pageNumber: number): Promise<void>;
  nextPage(): Promise<void>;
  previousPage(): Promise<void>;
  reload(): Promise<void>;
}

export const DEFAULT_PAGE_SIZE = 20;

export function useProducts(size: number = DEFAULT_PAGE_SIZE): UseProducts {
  const state = shallowRef<ProductsState>({ status: 'loading' });
  const page = ref<number>(0);
  const totalPages = ref<number>(0);
  const totalElements = ref<number>(0);

  /**
   * Monotonic request id. Paging quickly can land responses out of order; without
   * this, a slow page 0 arriving after a fast page 1 renders page 0's rows under
   * page 1's controls.
   */
  let requestSequence = 0;

  async function load(pageNumber: number = page.value): Promise<void> {
    const target = Math.max(0, pageNumber);
    const ticket = ++requestSequence;
    state.value = { status: 'loading' };

    try {
      const result = await apiFetch<PagedModel<Product>>('/api/products', {
        query: { page: target, size },
      });
      if (ticket !== requestSequence) {
        return;
      }
      page.value = result.page.number;
      totalPages.value = result.page.totalPages;
      totalElements.value = result.page.totalElements;
      state.value =
        result.content.length === 0
          ? { status: 'empty' }
          : { status: 'success', products: [...result.content] };
    } catch (error: unknown) {
      if (ticket !== requestSequence) {
        return;
      }
      const apiError = error instanceof ApiError ? error : null;
      state.value = {
        status: 'error',
        message: apiError?.message ?? 'The product catalog could not be loaded.',
        correlationId: apiError?.correlationId ?? null,
      };
    }
  }

  async function goToPage(pageNumber: number): Promise<void> {
    if (pageNumber < 0 || (totalPages.value > 0 && pageNumber >= totalPages.value)) {
      return;
    }
    await load(pageNumber);
  }

  return {
    state,
    page,
    size,
    totalPages: computed(() => totalPages.value),
    totalElements: computed(() => totalElements.value),
    hasPrevious: computed(() => page.value > 0),
    hasNext: computed(() => page.value + 1 < totalPages.value),
    load,
    goToPage,
    nextPage: () => goToPage(page.value + 1),
    previousPage: () => goToPage(page.value - 1),
    reload: () => load(page.value),
  };
}
