/**
 * Offline (demo-data) equivalent of GET /api/products filtering, sorting and paging, so the
 * storefront behaves the same when the API is unreachable.
 */

import type { Product, ProductQuery, ProductResponse } from '../types'

const SORTERS: Record<string, (a: Product, b: Product) => number> = {
  'price-asc': (a, b) => a.priceAmount - b.priceAmount,
  'price-desc': (a, b) => b.priceAmount - a.priceAmount,
  // NULLs last, like the backend.
  rating: (a, b) => (b.averageRating ?? -1) - (a.averageRating ?? -1),
  discount: (a, b) => (b.discountPercent ?? -1) - (a.discountPercent ?? -1),
}

export function queryLocally(
  products: Product[],
  query: ProductQuery,
  page: number,
  size: number,
): ProductResponse {
  const q = query.q?.trim().toLowerCase()
  const matches = products.filter(
    (p) =>
      (!q || p.name.toLowerCase().includes(q) || p.sku.toLowerCase().includes(q)) &&
      (!query.categoryId || p.categoryId === query.categoryId) &&
      (query.minPrice == null || p.priceAmount >= query.minPrice) &&
      (query.maxPrice == null || p.priceAmount <= query.maxPrice) &&
      (query.minRating == null || (p.averageRating ?? -1) >= query.minRating) &&
      (query.minDiscount == null || (p.discountPercent ?? -1) >= query.minDiscount),
  )

  const sorter = query.sort ? SORTERS[query.sort] : undefined
  const sorted = sorter ? [...matches].sort(sorter) : matches

  const totalPages = Math.ceil(sorted.length / size)
  return {
    content: sorted.slice(page * size, (page + 1) * size),
    pageable: { pageNumber: page, pageSize: size },
    totalElements: sorted.length,
    totalPages,
    isFirst: page === 0,
    isLast: page >= totalPages - 1,
  }
}
