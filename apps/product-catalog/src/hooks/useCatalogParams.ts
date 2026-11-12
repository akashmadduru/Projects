/**
 * Catalog search state, kept in the URL so results are shareable and Back/Forward work.
 *
 * URL keys: q, category, minPrice, maxPrice, rating, discount, seller, inStock, onSale, featured,
 * verifiedSeller, sort, page (1-based for humans).
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
  seller: 'seller',
  inStock: 'inStock',
  onSale: 'onSale',
  featured: 'featured',
  verifiedSeller: 'verifiedSeller',
  sort: 'sort',
} as const satisfies Record<Exclude<keyof ProductQuery, 'brand' | 'attrs' | 'sellerId'>, string>

const KEYS_BRAND = 'brand'
const ATTR_PREFIX = 'attr.'

function readList(raw: string | null): string[] {
  return raw ? raw.split(',').map((v) => v.trim()).filter(Boolean) : []
}

function readNumber(params: URLSearchParams, key: string): number | undefined {
  const raw = params.get(key)
  if (raw == null || raw === '') return undefined
  const value = Number(raw)
  return Number.isFinite(value) && value >= 0 ? value : undefined
}

/** A switch is on only when explicitly 'true' (or '1'); anything else is off. */
function readFlag(params: URLSearchParams, key: string): true | undefined {
  const raw = params.get(key)
  return raw === 'true' || raw === '1' ? true : undefined
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
    seller: params.get(KEYS.seller)?.trim() || undefined,
    inStock: readFlag(params, KEYS.inStock),
    onSale: readFlag(params, KEYS.onSale),
    featured: readFlag(params, KEYS.featured),
    verifiedSeller: readFlag(params, KEYS.verifiedSeller),
    sort: sort && SORTS.includes(sort) ? sort : undefined,
  }
  const brand = readList(params.get(KEYS_BRAND))
  if (brand.length) query.brand = brand
  const attrs: Record<string, string[]> = {}
  params.forEach((value, key) => {
    if (key.startsWith(ATTR_PREFIX)) {
      const values = readList(value)
      if (values.length) attrs[key.slice(ATTR_PREFIX.length)] = values
    }
  })
  if (Object.keys(attrs).length) query.attrs = attrs
  const page = Math.max((readNumber(params, 'page') ?? 1) - 1, 0)
  return { query, page }
}

export function toSearchString(query: ProductQuery, page = 0): string {
  const params = new URLSearchParams()
  for (const [field, key] of Object.entries(KEYS) as [keyof typeof KEYS, string][]) {
    const value = query[field]
    if (value != null && value !== '' && !(field === 'sort' && value === 'relevance')) {
      params.set(key, String(value))
    }
  }
  if (query.brand?.length) params.set(KEYS_BRAND, query.brand.join(','))
  for (const [attr, values] of Object.entries(query.attrs ?? {})) {
    if (values.length) params.set(ATTR_PREFIX + attr, values.join(','))
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
    query.minDiscount != null ||
    !!query.seller ||
    !!query.inStock ||
    !!query.onSale ||
    !!query.featured ||
    !!query.verifiedSeller ||
    !!query.brand?.length ||
    Object.keys(query.attrs ?? {}).length > 0
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
