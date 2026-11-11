import { describe, it, expect } from 'vitest'
import { hasActiveFilters, parseCatalogParams, toSearchString } from '../hooks/useCatalogParams'
import { toQueryParams } from '../services/productApi'
import { queryLocally } from './localCatalog'
import { dummyProducts } from './dummyProducts'

describe('catalog URL state', () => {
  it('round-trips a full query and a 1-based page', () => {
    const search = toSearchString(
      { q: 'lamp', categoryId: 'HOME', minPrice: 20, maxPrice: 80, minRating: 4, minDiscount: 15, sort: 'price-asc' },
      2,
    )

    expect(search).toBe('?q=lamp&category=HOME&minPrice=20&maxPrice=80&rating=4&discount=15&sort=price-asc&page=3')
    expect(parseCatalogParams(new URLSearchParams(search))).toEqual({
      query: { q: 'lamp', categoryId: 'HOME', minPrice: 20, maxPrice: 80, minRating: 4, minDiscount: 15, sort: 'price-asc' },
      page: 2,
    })
  })

  it('leaves defaults out of the URL', () => {
    expect(toSearchString({ sort: 'relevance' }, 0)).toBe('')
  })

  it('ignores malformed values instead of sending them to the API', () => {
    const { query, page } = parseCatalogParams(new URLSearchParams('?minPrice=abc&rating=-1&sort=bogus&page=0'))

    expect(query).toEqual({
      q: undefined,
      categoryId: undefined,
      minPrice: undefined,
      maxPrice: undefined,
      minRating: undefined,
      minDiscount: undefined,
      sort: undefined,
    })
    expect(page).toBe(0)
  })

  it('treats sort alone as unfiltered', () => {
    expect(hasActiveFilters({ sort: 'rating' })).toBe(false)
    expect(hasActiveFilters({ minPrice: 0 })).toBe(true)
  })
})

describe('toQueryParams', () => {
  it('maps UI sorts onto the backend sort whitelist', () => {
    expect(toQueryParams(0, 20, { sort: 'rating' })).toEqual({ page: 0, size: 20, sort: 'averageRating,desc' })
    expect(toQueryParams(0, 20, { sort: 'relevance' })).toEqual({ page: 0, size: 20 })
  })

  it('keeps a zero bound and drops a blank search', () => {
    expect(toQueryParams(1, 20, { minPrice: 0, q: '   ' })).toEqual({ page: 1, size: 20, minPrice: 0 })
  })
})

describe('queryLocally (offline demo data)', () => {
  it('filters, sorts and pages like the API', () => {
    const page = queryLocally(dummyProducts, { categoryId: 'ELECTRONICS', sort: 'price-asc' }, 0, 5)

    expect(page.content).toHaveLength(5)
    expect(page.content.every((p) => p.categoryId === 'ELECTRONICS')).toBe(true)
    const prices = page.content.map((p) => p.priceAmount)
    expect(prices).toEqual([...prices].sort((a, b) => a - b))
    expect(page.totalElements).toBe(dummyProducts.filter((p) => p.categoryId === 'ELECTRONICS').length)
  })

  it('puts unrated products last when sorting by rating', () => {
    const { content } = queryLocally(dummyProducts, { sort: 'rating' }, 0, dummyProducts.length)
    const firstUnrated = content.findIndex((p) => p.averageRating == null)

    expect(firstUnrated).toBeGreaterThan(0)
    expect(content.slice(firstUnrated).every((p) => p.averageRating == null)).toBe(true)
  })

  it('excludes unrated products from a rating filter', () => {
    const { content } = queryLocally(dummyProducts, { minRating: 4 }, 0, 100)

    expect(content.length).toBeGreaterThan(0)
    expect(content.every((p) => (p.averageRating ?? 0) >= 4)).toBe(true)
  })
})
