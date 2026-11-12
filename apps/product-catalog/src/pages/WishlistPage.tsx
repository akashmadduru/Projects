/**
 * Saved products. The wishlist stores ids only, so each product is fetched by id.
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { HeartIcon } from '../components/Icons'
import { ProductGrid } from '../components/ProductGrid'
import { SkeletonGrid } from '../components/Skeleton'
import { dummyProducts } from '../lib/dummyProducts'
import { productApi } from '../services/productApi'
import { useWishlistStore } from '../stores'
import type { Product } from '../types'

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
  const [products, setProducts] = useState<Product[] | undefined>()
  const idsKey = [...new Set(items.map((item) => item.productId))].join(',')

  useEffect(() => {
    let mounted = true
    const ids = idsKey ? idsKey.split(',') : []
    void Promise.all(ids.map(loadProduct)).then((loaded) => {
      if (mounted) setProducts(loaded.filter((p): p is Product => p !== undefined))
    })
    return () => {
      mounted = false
    }
  }, [idsKey])

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
      ) : products === undefined ? (
        <SkeletonGrid count={Math.min(items.length, 8)} />
      ) : (
        <ProductGrid products={products} />
      )}
    </div>
  )
}
