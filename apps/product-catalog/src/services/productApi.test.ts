import { describe, it, expect, beforeEach } from 'vitest'
import { productApi, toProduct, toSearchParams, usesSmartSearch } from './productApi'
import { clearRequestCache } from '../lib/http'
import { backendBook } from '../test/fixtures'
import type { ProductQuery } from '../types'

describe('toProduct', () => {
  it('falls back to thumbnailUrl when the list row carries no images', () => {
    expect(toProduct(backendBook).images).toEqual([backendBook.thumbnailUrl])
  })

  it('orders images by displayOrder and prefers them over the thumbnail', () => {
    const product = toProduct({
      ...backendBook,
      images: [
        { id: 'b', url: 'https://img.example/b.png', displayOrder: 1 },
        { id: 'a', url: 'https://img.example/a.png', displayOrder: 0 },
      ],
    })

    expect(product.images).toEqual(['https://img.example/a.png', 'https://img.example/b.png'])
  })

  it('yields no images when the backend sends neither images nor a thumbnail', () => {
    expect(toProduct({ ...backendBook, images: null, thumbnailUrl: null }).images).toEqual([])
  })

  it('keeps the enrichment fields as sent', () => {
    const product = toProduct(backendBook)

    expect(product.listPrice).toBe(18.69)
    expect(product.discountPercent).toBe(9.1)
    expect(product.averageRating).toBe(4.5)
    expect(product.featured).toBe(false)
    expect(product.sellerVerified).toBe(true)
  })
})

describe('productApi.list', () => {
  beforeEach(() => clearRequestCache())

  it('normalizes the Spring PagedModel envelope', async () => {
    const page = await productApi.list(0, 20)

    expect(page.totalElements).toBe(100)
    expect(page.totalPages).toBe(5)
    expect(page.pageable).toEqual({ pageNumber: 0, pageSize: 20 })
    expect(page.isFirst).toBe(true)
    expect(page.isLast).toBe(false)
    expect(page.content[0]?.images).toEqual([backendBook.thumbnailUrl])
    expect(page.content[1]?.images[0]).toBe('https://img.example/first.png')
  })
})

describe('usesSmartSearch (Cartwise AI-1 routing)', () => {
  it('needs at least two characters of search text', () => {
    expect(usesSmartSearch({})).toBe(false)
    expect(usesSmartSearch({ q: ' a ' })).toBe(false)
    expect(usesSmartSearch({ q: 'laptop' })).toBe(true)
  })

  it('accepts the filters the search endpoint supports', () => {
    expect(
      usesSmartSearch({
        q: 'laptop',
        categoryId: 'c1',
        minPrice: 10,
        maxPrice: 100,
        minRating: 4,
        brand: ['Acme'],
        sort: 'price-asc',
      }),
    ).toBe(true)
  })

  it.each<[string, ProductQuery]>([
    ['attribute facets', { attrs: { color: ['red'] } }],
    ['seller', { seller: 'Acme Sellers' }],
    ['in stock', { inStock: true }],
    ['on sale', { onSale: true }],
    ['min discount', { minDiscount: 10 }],
    ['rating sort', { sort: 'rating' }],
    ['discount sort', { sort: 'discount' }],
  ])('keeps %s on the catalog listing so no filter is dropped', (_label, extra) => {
    expect(usesSmartSearch({ q: 'laptop', ...extra })).toBe(false)
  })

  it('ignores empty attribute selections', () => {
    expect(usesSmartSearch({ q: 'laptop', attrs: { color: [] } })).toBe(true)
  })
})

describe('toSearchParams', () => {
  it('maps the query to hybrid search parameters', () => {
    expect(
      toSearchParams(2, 20, { q: ' travel laptop ', brand: ['Acme', 'Zed'], sort: 'newest', maxPrice: 60000 }),
    ).toEqual({ q: 'travel laptop', mode: 'hybrid', page: 2, size: 20, brand: 'Acme,Zed', sort: 'newest', maxPrice: 60000 })
  })
})

describe('productApi.list with search text', () => {
  beforeEach(() => clearRequestCache())

  it('uses hybrid search and exposes its metadata', async () => {
    const page = await productApi.list(0, 20, { q: 'travel laptop' })

    expect(page.totalElements).toBe(100)
    expect(page.search).toEqual({
      effectiveMode: 'hybrid',
      degraded: false,
      interpretedFilters: { minPrice: null, maxPrice: 60000, minRating: null, brand: null },
    })
  })

  it('stays on the catalog listing when a filter search cannot honour is set', async () => {
    const page = await productApi.list(0, 20, { q: 'travel laptop', inStock: true })

    expect(page.totalElements).toBe(100)
    expect(page.search).toBeUndefined()
  })
})
