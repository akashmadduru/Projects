/**
 * Horizontal product row that hydrates itself from a loader: recently viewed, also viewed, deals.
 * Renders nothing while empty or on error, so a missing backend never leaves a hole in the page.
 */

import { useEffect, useState } from 'react'
import { Carousel } from './Carousel'
import { ProductCard } from './ProductCard'
import { discoveryApi } from '../services/discoveryApi'
import { getRecentlyViewed } from '../lib/recentlyViewed'
import type { Product } from '../types'

interface ShelfProps {
  title: string
  headingId: string
  products: Product[]
}

export function Shelf({ title, headingId, products }: ShelfProps) {
  if (products.length === 0) return null
  return (
    <section aria-labelledby={headingId} className="space-y-4">
      <h2 id={headingId} className="font-display text-2xl font-extrabold text-on-surface">
        {title}
      </h2>
      <Carousel label={title} itemMinWidth={224} className="px-1">
        {products.map((p) => (
          <div key={p.id} className="h-full">
            <ProductCard product={p} />
          </div>
        ))}
      </Carousel>
    </section>
  )
}

function useShelf(load: () => Promise<Product[]>, key: string): Product[] {
  const [products, setProducts] = useState<Product[]>([])
  useEffect(() => {
    let live = true
    setProducts([])
    load()
      .then((p) => {
        if (live) setProducts(p)
      })
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [key])
  return products
}

export function RecentlyViewedShelf({ excludeId }: { excludeId?: string }) {
  const products = useShelf(
    () => discoveryApi.batch(getRecentlyViewed().filter((id) => id !== excludeId).slice(0, 12)),
    excludeId ?? 'all',
  )
  return <Shelf title="Recently viewed" headingId="recent-heading" products={products} />
}

export function AlsoViewedShelf({ productId }: { productId: string }) {
  const products = useShelf(() => discoveryApi.alsoViewed(productId, 8), productId)
  return <Shelf title="Customers also viewed" headingId="also-viewed-heading" products={products} />
}
