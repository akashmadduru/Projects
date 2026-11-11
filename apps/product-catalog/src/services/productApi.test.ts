import { describe, it, expect, beforeEach } from 'vitest'
import { productApi, toProduct } from './productApi'
import { clearRequestCache } from '../lib/http'
import { backendBook } from '../test/fixtures'

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
