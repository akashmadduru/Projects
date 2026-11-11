/**
 * Responsive product grid layout - with refined stagger timing and no layout shift
 */

import { ProductCard } from './ProductCard'
import type { Product } from '../types'

interface Props {
  products: Product[]
  showMetadata?: boolean
}

export function ProductGrid({ products, showMetadata = false }: Props) {
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
              animation: `slideUpStagger 0.6s ease-out ${index * 0.05}s both`,
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
