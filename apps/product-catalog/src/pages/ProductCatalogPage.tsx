/**
 * Catalog listing: promo banner, filter sidebar, sort bar, results grid and pagination
 */

import { lazy, Suspense, useState } from 'react'
import { ActiveFilters } from '../components/ActiveFilters'
import { CategoryTiles } from '../components/CategoryTiles'
import { DealsStrip } from '../components/DealsStrip'
import { useFacets } from '../hooks/useFacets'
import { Drawer } from '../components/Drawer'
import { FilterPanel } from '../components/FilterPanel'
import { FilterIcon } from '../components/Icons'
import { Pagination } from '../components/Pagination'
import { ProductGrid } from '../components/ProductGrid'
import { PromoBanner } from '../components/PromoBanner'
import { SkeletonGrid } from '../components/Skeleton'
import { SortSelect } from '../components/SortSelect'
import { hasActiveFilters, useCatalogParams, useCategories, useProducts } from '../hooks'
import { usePaginationStore } from '../stores'

const PersonalisedHome = lazy(() => import('../components/PersonalisedHome'))

export function ProductCatalogPage() {
  const { query, page, update, setPage, clearFilters } = useCatalogParams()
  const { categories, tree } = useCategories()
  const pageSize = usePaginationStore((s) => s.size)
  const state = useProducts(query, page)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const facets = useFacets(query)

  const filtered = hasActiveFilters(query)
  const categoryName = categories.find((c) => c.id === query.categoryId)?.name
  const heading = query.q ? `Results for “${query.q}”` : (categoryName ?? 'All products')
  const activeFilterCount = [
    query.categoryId,
    query.minPrice ?? query.maxPrice,
    query.minRating,
    query.minDiscount,
    query.seller,
    query.inStock,
    query.onSale,
    query.featured,
    query.verifiedSeller,
    query.brand?.length ? true : undefined,
    query.attrs && Object.keys(query.attrs).length ? true : undefined,
  ].filter((v) => v != null).length

  const panel = <FilterPanel query={query} categories={tree} onChange={update} facets={facets} />

  return (
    <div className="space-y-6">
      {!filtered && page === 0 && (
        <>
          <PromoBanner />
          <CategoryTiles categories={tree} />
          <DealsStrip />
          <Suspense fallback={null}>
            <PersonalisedHome />
          </Suspense>
        </>
      )}

      <div className="grid gap-6 lg:grid-cols-[260px_1fr]">
        <aside aria-label="Filters" className="hidden lg:block">
          <div className="sticky top-28 rounded-[1.75rem] border-2 border-outline bg-surface p-5 shadow-card">
            <h2 className="mb-4 flex items-center gap-2 text-base font-bold text-on-surface">
              <span className="bg-cta-gradient flex h-8 w-8 items-center justify-center rounded-full border-2 border-ink text-on-accent">
                <FilterIcon className="w-4 h-4" />
              </span>
              Filters
            </h2>
            {panel}
          </div>
        </aside>

        <section aria-labelledby="results-heading" className="min-w-0 space-y-4">
          <div className="relative flex flex-wrap items-end justify-between gap-3 overflow-hidden rounded-[1.75rem] border-2 border-outline bg-surface px-5 pb-4 pt-5 shadow-card">
            <span aria-hidden="true" className="retro-stripes absolute inset-x-0 top-0 h-1.5 border-b-2 border-ink" />
            <div className="min-w-0">
              <h2 id="results-heading" className="truncate font-display text-2xl font-extrabold text-on-surface">
                {heading}
              </h2>
              <p className="text-sm text-on-surface-muted" aria-live="polite">
                {state.status === 'success' ? (
                  <span key={state.data.totalElements} className="inline-block animate-pop">
                    <span className="font-bold text-primary">{state.data.totalElements.toLocaleString()}</span>{' '}
                    {state.data.totalElements === 1 ? 'result' : 'results'}
                  </span>
                ) : (
                  'Loading…'
                )}
                {state.status === 'success' && state.isDummyData && (
                  <span className="ml-2 rounded bg-warning/15 px-1.5 py-0.5 text-xs font-semibold text-warning">
                    Offline demo data
                  </span>
                )}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setFiltersOpen(true)}
                className="press inline-flex items-center gap-2 rounded-full border-2 border-outline bg-surface px-4 py-2 shadow-cta text-sm font-medium text-on-surface hover:border-primary/50 hover:text-primary lg:hidden"
              >
                <FilterIcon className="w-4 h-4" />
                Filters
                {activeFilterCount > 0 && (
                  <span key={activeFilterCount} className="bg-cta-gradient animate-bump rounded-full px-1.5 text-xs font-bold text-on-accent">
                    {activeFilterCount}
                  </span>
                )}
              </button>
              <SortSelect value={query.sort} onChange={(sort) => update({ sort })} />
            </div>
          </div>

          <ActiveFilters query={query} categories={categories} onChange={update} onClearAll={clearFilters} />

          {state.status === 'success' ? (
            state.data.content.length === 0 ? (
              <div className="rounded-2xl border border-dashed border-primary/40 bg-surface px-6 py-16 text-center shadow-card">
                <p className="text-lg font-semibold text-on-surface">No products match your filters</p>
                <p className="mt-1 text-sm text-on-surface-muted">Try a broader search or remove a filter.</p>
                {filtered && (
                  <button
                    type="button"
                    onClick={clearFilters}
                    className="press shine bg-cta-gradient mt-5 rounded-xl px-5 py-2.5 font-semibold text-on-accent shadow-cta"
                  >
                    Clear all filters
                  </button>
                )}
              </div>
            ) : (
              <>
                <ProductGrid products={state.data.content} />
                <Pagination
                  page={page}
                  pageSize={pageSize}
                  totalPages={state.data.totalPages}
                  totalElements={state.data.totalElements}
                  onPageChange={setPage}
                />
              </>
            )
          ) : (
            <SkeletonGrid count={8} />
          )}
        </section>
      </div>

      <Drawer
        open={filtersOpen}
        onClose={() => setFiltersOpen(false)}
        title="Filters"
        side="left"
        footer={
          <button
            type="button"
            onClick={() => setFiltersOpen(false)}
            className="press shine bg-cta-gradient w-full rounded-xl py-3 font-semibold text-on-accent shadow-cta"
          >
            Show {state.status === 'success' ? state.data.totalElements.toLocaleString() : ''} results
          </button>
        }
      >
        {panel}
      </Drawer>
    </div>
  )
}
