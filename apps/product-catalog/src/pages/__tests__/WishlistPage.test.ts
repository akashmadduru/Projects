/**
 * Wishlist cards are per saved row: a saved variant shows its own options, price and image.
 */

import { describe, it, expect } from 'vitest'
import { wishlistEntry } from '../WishlistPage'
import { singleProduct } from '../../test/fixtures'
import type { Product, ProductVariant } from '../../types'

const variant = (id: string, extra: Partial<ProductVariant> = {}): ProductVariant => ({
  id,
  sku: `SKU-${id}`,
  attributes: { Color: 'Red', Size: 'M' },
  priceAmount: 30,
  salePrice: null,
  imageUrl: null,
  active: true,
  availableQuantity: 5,
  ...extra,
})

const product: Product = {
  ...singleProduct,
  priceAmount: 25,
  images: ['/a.png', '/b.png'],
  variants: [variant('v1'), variant('v2', { attributes: { Color: 'Blue', Size: 'L' }, salePrice: 27, imageUrl: '/b.png' })],
}

describe('wishlistEntry', () => {
  it('a product saved without a variant is the plain card', () => {
    expect(wishlistEntry({ productId: product.id, addedAt: 0 }, product)).toEqual({ key: product.id, product })
  })

  it('a saved variant gets its options, its price and its image first', () => {
    const entry = wishlistEntry({ productId: product.id, variantId: 'v2', addedAt: 0 }, product)

    expect(entry.key).toBe(`${product.id}:v2`)
    expect(entry.saved).toEqual({ variantId: 'v2', label: 'Blue / L', image: '/b.png' })
    expect(entry.product.priceAmount).toBe(27)
    expect(entry.product.images).toEqual(['/b.png', '/a.png'])
  })

  it('two variants of one product are two cards', () => {
    const keys = ['v1', 'v2'].map((v) => wishlistEntry({ productId: product.id, variantId: v, addedAt: 0 }, product).key)
    expect(new Set(keys).size).toBe(2)
  })

  it('a variant that no longer exists keeps its row so the heart can remove it', () => {
    const entry = wishlistEntry({ productId: product.id, variantId: 'gone', addedAt: 0 }, product)
    expect(entry.saved?.variantId).toBe('gone')
    expect(entry.saved?.label).toMatch(/no longer available/)
  })
})
