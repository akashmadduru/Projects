/**
 * Seller storefront at /seller/:slug: profile, rating and the seller's products
 * (catalog list filtered by sellerId). Lazy-loaded.
 */

import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ErrorAlert } from '../components/ErrorAlert'
import { LoadingBlock, cardClass } from '../components/Form'
import { Pagination } from '../components/Pagination'
import { ProductGrid } from '../components/ProductGrid'
import { RatingChip } from '../components/RatingChip'
import { SkeletonGrid } from '../components/Skeleton'
import { useLoad } from '../hooks/useLoad'
import { useHead } from '../hooks/useHead'
import { errorMessage, isApiError } from '../lib/errors'
import { productApi } from '../services/productApi'
import { sellerApi } from '../services/sellerApi'

const PAGE_SIZE = 12

function SellerProducts({ sellerId }: { sellerId: string }) {
  const [page, setPage] = useState(0)
  useEffect(() => setPage(0), [sellerId])
  const load = useCallback(() => productApi.list(page, PAGE_SIZE, { sellerId }), [page, sellerId])
  const { data, error, loading } = useLoad(load)

  if (error) return <ErrorAlert message={errorMessage(error)} />
  if (!data || loading) return <SkeletonGrid count={8} />
  if (data.content.length === 0) {
    return <p className="rounded-2xl border border-dashed border-outline px-6 py-12 text-center text-on-surface-muted">This seller has no products yet.</p>
  }
  return (
    <>
      <ProductGrid products={data.content} />
      <Pagination page={page} pageSize={PAGE_SIZE} totalPages={data.totalPages} totalElements={data.totalElements} onPageChange={setPage} />
    </>
  )
}

export default function SellerPage() {
  const { slug = '' } = useParams<{ slug: string }>()
  const load = useCallback(() => sellerApi.profile(slug), [slug])
  const { data: seller, error, loading } = useLoad(load)

  useHead(seller ? { title: `${seller.displayName} | Cartwise`, description: `Shop products sold by ${seller.displayName} on Cartwise` } : undefined)

  if (loading && !seller) return <LoadingBlock label="Loading seller" />
  if (error || !seller) {
    const notFound = isApiError(error) && error.status === 404
    return (
      <div className="mx-auto max-w-md rounded-xl border border-outline bg-surface px-6 py-16 text-center shadow-card">
        <h1 className="text-xl font-bold text-on-surface">{notFound || !error ? "We couldn't find that seller" : 'Could not load this seller'}</h1>
        {!!error && !notFound && <p className="mt-2 text-sm text-on-surface-muted">{errorMessage(error)}</p>}
        <Link to="/" className="press shine bg-cta-gradient mt-6 inline-block rounded-xl px-5 py-2.5 font-semibold text-on-accent shadow-cta">
          Continue shopping
        </Link>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <header className={`${cardClass} flex flex-wrap items-center justify-between gap-4`}>
        <div className="space-y-2">
          <h1 className="font-display text-2xl font-extrabold text-on-surface sm:text-3xl">{seller.displayName}</h1>
          <div className="flex flex-wrap items-center gap-3 text-sm text-on-surface-muted">
            <RatingChip rating={seller.ratingAvg} count={seller.ratingCount} size="md" />
            <span>
              Joined {new Date(seller.joinedAt).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}
            </span>
            <span>{seller.productCount.toLocaleString()} {seller.productCount === 1 ? 'product' : 'products'}</span>
          </div>
        </div>
      </header>
      <section aria-labelledby="seller-products-heading" className="space-y-4">
        <h2 id="seller-products-heading" className="font-display text-xl font-extrabold text-on-surface">
          Products from {seller.displayName}
        </h2>
        <SellerProducts sellerId={seller.id} />
      </section>
    </div>
  )
}
