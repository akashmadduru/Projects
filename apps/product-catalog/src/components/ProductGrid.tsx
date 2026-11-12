/**
 * Responsive product grid layout - with refined stagger timing and no layout shift
 */

import { useEffect, useRef } from 'react'
import { ProductCard } from './ProductCard'
import { sellerApi } from '../services/sellerApi'
import type { Product } from '../types'

interface Props {
  products: Product[]
  showMetadata?: boolean
}

export function ProductGrid({ products, showMetadata = false }: Props) {
  // One impression per sponsored ad per mounted grid.
  const reported = useRef(new Set<string>())
  useEffect(() => {
    const fresh = products.flatMap((p) => (p.sponsored && p.adId && !reported.current.has(p.adId) ? [p.adId] : []))
    if (fresh.length === 0) return
    fresh.forEach((adId) => reported.current.add(adId))
    sellerApi.trackImpressions(fresh)
  }, [products])

  if (products.length === 0) {
    return (
      <div className="text-center py-16 bg-surface-variant/30 rounded-lg">
        <p className="text-lg text-on-surface-muted">No products found</p>
      </div>
    )
  }

  return (
    <div className="mb-8 animate-fade-in">
      {showMetadata && (
        <div className="mb-6 flex items-center justify-between">
          <p className="text-sm text-on-surface-muted">
            Showing {products.length} product{products.length !== 1 ? 's' : ''}
          </p>
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-3 xl:grid-cols-4">
        {products.map((product, index) => (
          <div
            key={product.id}
            style={{
              animation: `slideUpStagger 0.9s cubic-bezier(0.22,1,0.36,1) ${index * 0.07}s both`,
              transform: 'translateY(0)',
            }}
            className="will-change-transform"
          >
            <ProductCard product={product} />
          </div>
        ))}
      </div>
    </div>
  )
}
