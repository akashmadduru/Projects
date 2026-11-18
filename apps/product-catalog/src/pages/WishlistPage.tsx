/**
 * Saved products, one card per saved row: a product saved with a variant shows that variant's
 * options, price and image and links to it. The wishlist stores ids only, so each product is
 * fetched once by id.
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { HeartIcon } from '../components/Icons'
import { ProductCard } from '../components/ProductCard'
import { SkeletonGrid } from '../components/Skeleton'
import { dummyProducts } from '../lib/dummyProducts'
import { productApi } from '../services/productApi'
import { useWishlistStore } from '../stores'
import type { Product } from '../types'
import type { WishlistItem } from '../stores/wishlistStore'

interface Entry {
  key: string
  product: Product
  saved?: { variantId: string; label: string; image?: string | null }
}

/** The card for one saved row; a variant overrides the product's price and lead image. */
export function wishlistEntry(item: WishlistItem, product: Product): Entry {
  if (!item.variantId) return { key: item.productId, product }
  const variant = product.variants?.find((v) => v.id === item.variantId)
  const key = `${item.productId}:${item.variantId}`
  if (!variant) {
    return { key, product, saved: { variantId: item.variantId, label: 'This option is no longer available' } }
  }
  const price = variant.salePrice && variant.salePrice > 0 ? variant.salePrice : variant.priceAmount
  const images = variant.imageUrl ? [variant.imageUrl, ...product.images.filter((u) => u !== variant.imageUrl)] : product.images
  return {
    key,
    product: { ...product, priceAmount: price ?? product.priceAmount, images },
    saved: { variantId: variant.id, label: Object.values(variant.attributes).join(' / '), image: variant.imageUrl },
  }
}

async function loadProduct(id: string): Promise<Product | undefined> {
  try {
    return await productApi.detail(id)
  } catch (error) {
    // A 404 means the product is gone; anything else is the API being unreachable.
    return (error as { status?: number }).status === 404 ? undefined : dummyProducts.find((p) => p.id === id)
  }
}

export function WishlistPage() {
  const items = useWishlistStore((s) => s.items)
  const [products, setProducts] = useState<Map<string, Product> | undefined>()
  const idsKey = [...new Set(items.map((item) => item.productId))].join(',')

  useEffect(() => {
    let mounted = true
    const ids = idsKey ? idsKey.split(',') : []
    void Promise.all(ids.map(loadProduct)).then((loaded) => {
      if (mounted) setProducts(new Map(loaded.filter((p): p is Product => p !== undefined).map((p) => [p.id, p])))
    })
    return () => {
      mounted = false
    }
  }, [idsKey])

  const entries = products
    ? items.flatMap((item) => {
        const product = products.get(item.productId)
        return product ? [wishlistEntry(item, product)] : []
      })
    : undefined

  return (
    <div className="space-y-5">
      <div>
        <h1 className="font-display text-2xl font-bold text-on-surface">Your wishlist</h1>
        <p className="text-sm text-on-surface-muted">
          {items.length} saved {items.length === 1 ? 'item' : 'items'}
        </p>
      </div>

      {items.length === 0 ? (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-outline bg-surface px-6 py-16 text-center shadow-card">
          <HeartIcon className="w-12 h-12 text-on-surface-muted" />
          <p className="text-lg font-semibold text-on-surface">Nothing saved yet</p>
          <p className="text-sm text-on-surface-muted">Tap the heart on any product to keep it here for later.</p>
          <Link to="/" className="press shine bg-cta-gradient mt-2 rounded-xl px-5 py-2.5 font-semibold text-on-accent shadow-cta">
            Explore products
          </Link>
        </div>
      ) : entries === undefined ? (
        <SkeletonGrid count={Math.min(items.length, 8)} />
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-3 xl:grid-cols-4">
          {entries.map((entry) => (
            <ProductCard key={entry.key} product={entry.product} saved={entry.saved} />
          ))}
        </div>
      )}
    </div>
  )
}
