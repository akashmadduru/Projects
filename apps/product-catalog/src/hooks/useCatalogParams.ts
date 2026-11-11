/**
 * Catalog search state, kept in the URL so results are shareable and Back/Forward work.
 *
 * URL keys: q, category, minPrice, maxPrice, rating, discount, sort, page (1-based for humans).
 */

import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { ProductQuery, ProductSort } from '../types'

const SORTS: ProductSort[] = ['relevance', 'price-asc', 'price-desc', 'rating', 'discount', 'newest']

const KEYS = {
  q: 'q',
  categoryId: 'category',
  minPrice: 'minPrice',
  maxPrice: 'maxPrice',
  minRating: 'rating',
  minDiscount: 'discount',
  sort: 'sort',
} as const satisfies Record<keyof ProductQuery, string>

function readNumber(params: URLSearchParams, key: string): number | undefined {
  const raw = params.get(key)
  if (raw == null || raw === '') return undefined
  const value = Number(raw)
  return Number.isFinite(value) && value >= 0 ? value : undefined
}

export function parseCatalogParams(params: URLSearchParams): { query: ProductQuery; page: number } {
  const sort = params.get(KEYS.sort) as ProductSort | null
  const query: ProductQuery = {
    q: params.get(KEYS.q)?.trim() || undefined,
    categoryId: params.get(KEYS.categoryId) || undefined,
    minPrice: readNumber(params, KEYS.minPrice),
    maxPrice: readNumber(params, KEYS.maxPrice),
    minRating: readNumber(params, KEYS.minRating),
    minDiscount: readNumber(params, KEYS.minDiscount),
    sort: sort && SORTS.includes(sort) ? sort : undefined,
  }
  const page = Math.max((readNumber(params, 'page') ?? 1) - 1, 0)
  return { query, page }
}

export function toSearchString(query: ProductQuery, page = 0): string {
  const params = new URLSearchParams()
  for (const [field, key] of Object.entries(KEYS) as [keyof ProductQuery, string][]) {
    const value = query[field]
    if (value != null && value !== '' && !(field === 'sort' && value === 'relevance')) {
      params.set(key, String(value))
    }
  }
  if (page > 0) params.set('page', String(page + 1))
  const search = params.toString()
  return search ? `?${search}` : ''
}

export function hasActiveFilters(query: ProductQuery): boolean {
  return (
    !!query.q ||
    !!query.categoryId ||
    query.minPrice != null ||
    query.maxPrice != null ||
    query.minRating != null ||
    query.minDiscount != null
  )
}

export function useCatalogParams() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { query, page } = useMemo(() => parseCatalogParams(searchParams), [searchParams])

  /** Merge a change into the query. Any filter change restarts at page 1. */
  const update = useCallback(
    (patch: Partial<ProductQuery>) => {
      setSearchParams(toSearchString({ ...query, ...patch }, 0))
    },
    [query, setSearchParams],
  )

  const setPage = useCallback(
    (next: number) => setSearchParams(toSearchString(query, next)),
    [query, setSearchParams],
  )

  const clearFilters = useCallback(
    () => setSearchParams(toSearchString({ sort: query.sort }, 0)),
    [query.sort, setSearchParams],
  )

  return { query, page, update, setPage, clearFilters }
}
