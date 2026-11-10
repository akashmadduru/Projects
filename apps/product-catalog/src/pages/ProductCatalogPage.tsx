/**
 * Product catalog main page with pagination and demo data indicator
 */

import { useProducts } from '../hooks'
import { ProductGrid, Pagination, SkeletonGrid } from '../components'

export function ProductCatalogPage() {
  const state = useProducts()

  if (state.status === 'loading') {
    return (
      <div className="animate-fade-in">
        <SkeletonGrid count={20} />
      </div>
    )
  }

  if (state.status === 'success') {
    const { content, totalPages, isFirst, isLast } = state.data

    return (
      <div className="animate-fade-in">
        <div className="mb-8">
          <div className="flex items-center justify-between gap-4 mb-4">
            <div>
              <h2 className="text-3xl font-bold text-on-surface mb-2">Products</h2>
              <p className="text-muted">
                Browse our complete catalog of {state.data.totalElements} products
              </p>
            </div>
            {state.isDummyData && (
              <div className="glass rounded-lg px-4 py-2 border border-outline/50 animate-slide-up" style={{backgroundColor: 'hsl(var(--color-outline) / 0.1)'}}>
                <span className="text-xs font-semibold text-on-surface flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full animate-pulse" style={{backgroundColor: 'hsl(var(--color-outline) / 0.8)'}} />
                  Demo Data
                </span>
              </div>
            )}
          </div>
        </div>

        {content.length === 0 ? (
          <div className="text-center py-16 glass rounded-2xl mb-8">
            <p className="text-lg text-muted">No products found</p>
          </div>
        ) : (
          <>
            <ProductGrid products={content} />
            <div className="animate-slide-up">
              <Pagination totalPages={totalPages} isFirst={isFirst} isLast={isLast} />
            </div>
          </>
        )}
      </div>
    )
  }

  return null
}
