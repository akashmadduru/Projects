/**
 * Product detail: breadcrumb, gallery, buy box, specifications, ratings, seller, similar products
 */

import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AddToCartButton } from '../components/AddToCartButton'
import { Seal, Starburst } from '../components/Atomic'
import { CheckBadgeIcon, ChevronRightIcon, MinusIcon, PlusIcon } from '../components/Icons'
import { PriceBlock } from '../components/PriceBlock'
import { ProductCard } from '../components/ProductCard'
import { ProductImage } from '../components/ProductImage'
import { RatingBreakdown } from '../components/RatingBreakdown'
import { RatingChip } from '../components/RatingChip'
import { SpecificationTable } from '../components/SpecificationTable'
import { WishlistButton } from '../components/WishlistButton'
import { toSearchString, useCategories, useProduct, useProducts } from '../hooks'
import { effectiveDiscount, formatCount, formatMoney } from '../lib/format'
import type { Product } from '../types'

const MAX_QUANTITY = 10
const LOW_STOCK = 10

function productDetails(product: Product): Record<string, unknown> {
  const { dimensionsLength: l, dimensionsWidth: w, dimensionsHeight: h } = product
  return {
    ...(product.specifications ?? {}),
    ...(product.material && { material: product.material }),
    ...(product.color && { color: product.color }),
    ...(product.weightGrams != null && { weight: `${product.weightGrams} g` }),
    ...(l != null && w != null && h != null && { dimensions: `${l} × ${w} × ${h} cm` }),
    sku: product.sku,
  }
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-[1.75rem] border-2 border-outline bg-surface p-5 shadow-card transition-shadow duration-300 hover:shadow-card-hover sm:p-6">
      <h2 className="mb-4 flex items-center gap-2 text-lg font-bold text-on-surface">
        <Starburst className="h-5 w-5 text-accent-2" />
        {title}
      </h2>
      {children}
    </section>
  )
}

function StockLine({ quantity }: { quantity: number | null }) {
  if (quantity == null) return null
  if (quantity === 0) return <p className="font-semibold text-error">Currently out of stock</p>
  if (quantity <= LOW_STOCK) return <p className="font-semibold text-error">Hurry, only {quantity} left in stock</p>
  return <p className="font-semibold text-success">In stock</p>
}

export function ProductDetailPage() {
  const { id } = useParams<{ id: string }>()
  const state = useProduct(id)
  const { categories } = useCategories()
  const [imageIndex, setImageIndex] = useState(0)
  const [quantity, setQuantity] = useState(1)

  // The route reuses this component when moving to a similar product.
  useEffect(() => {
    setImageIndex(0)
    setQuantity(1)
  }, [id])

  const product = state.status === 'success' ? state.data : undefined
  const similar = useProducts(product?.categoryId ? { categoryId: product.categoryId } : {}, 0, 9)

  if (state.status === 'loading') {
    return (
      <div className="grid gap-8 lg:grid-cols-2" role="status" aria-label="Loading product">
        <div className="aspect-square rounded-xl skeleton-loading" />
        <div className="space-y-4">
          <div className="h-8 w-3/4 rounded skeleton-loading" />
          <div className="h-5 w-1/3 rounded skeleton-loading" />
          <div className="h-10 w-1/2 rounded skeleton-loading" />
          <div className="h-12 w-full rounded-lg skeleton-loading" />
        </div>
      </div>
    )
  }

  if (!product) {
    return (
      <div className="mx-auto max-w-md rounded-xl border border-outline bg-surface px-6 py-16 text-center shadow-card">
        <h1 className="text-xl font-bold text-on-surface">We couldn&apos;t find that product</h1>
        <p className="mt-2 text-sm text-on-surface-muted">It may have been removed or the link is incorrect.</p>
        <Link to="/" className="press shine bg-cta-gradient mt-6 inline-block rounded-xl px-5 py-2.5 font-semibold text-on-accent shadow-cta">
          Continue shopping
        </Link>
      </div>
    )
  }

  const category = categories.find((c) => c.id === product.categoryId)
  const parent = categories.find((c) => c.id === category?.parentCategoryId)
  const images = product.images
  const discount = effectiveDiscount(product.listPrice, product.priceAmount)
  const saving =
    discount != null && product.listPrice != null
      ? Math.round((product.listPrice - product.priceAmount) * 100) / 100
      : null
  const activeImage = images[Math.min(imageIndex, images.length - 1)]
  const maxQuantity = Math.max(1, Math.min(MAX_QUANTITY, product.lastKnownQuantity ?? MAX_QUANTITY))
  const similarProducts =
    similar.status === 'success' ? similar.data.content.filter((p) => p.id !== product.id).slice(0, 8) : []

  return (
    <div className="space-y-6">
      <nav aria-label="Breadcrumb">
        <ol className="flex flex-wrap items-center gap-1 text-sm text-on-surface-muted">
          <li>
            <Link to="/" className="hover:text-primary">
              Home
            </Link>
          </li>
          {[parent, category].filter(Boolean).map((c) => (
            <li key={c!.id} className="flex items-center gap-1">
              <ChevronRightIcon className="w-4 h-4" />
              <Link to={{ pathname: '/', search: toSearchString({ categoryId: c!.id }) }} className="hover:text-primary">
                {c!.name}
              </Link>
            </li>
          ))}
          <li className="flex min-w-0 items-center gap-1">
            <ChevronRightIcon className="w-4 h-4 shrink-0" />
            <span className="truncate text-on-surface" aria-current="page">
              {product.name}
            </span>
          </li>
        </ol>
      </nav>

      {state.status === 'success' && state.isDummyData && (
        <p className="rounded-lg bg-warning/15 px-4 py-2 text-sm font-medium text-warning">
          You&apos;re viewing offline demo data — the catalog service is unreachable.
        </p>
      )}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        {/* Gallery */}
        <div className="space-y-3 lg:sticky lg:top-24 lg:self-start">
          <div className="group relative aspect-square overflow-hidden rounded-[2rem] border-2 border-outline bg-surface-variant shadow-card">
            <ProductImage src={activeImage} alt={product.name} productId={product.id} />
            <div className="pointer-events-none absolute left-4 top-4 flex flex-col items-start gap-2">
              {product.featured && (
                <span className="rounded-full border-2 border-ink bg-vivid-amber px-3 py-1 text-xs font-bold text-ink shadow-[2px_2px_0_hsl(var(--color-ink))]">Top pick</span>
              )}
              {discount != null && (
                <span className="relative flex h-20 w-20 items-center justify-center">
                  <Seal className="absolute inset-0 h-full w-full text-deal drop-shadow-[3px_3px_0_hsl(var(--color-ink))]" />
                  <span className="font-display relative -rotate-12 text-lg text-white">-{discount}%</span>
                </span>
              )}
            </div>
          </div>
          {images.length > 1 && (
            <div className="flex gap-2 overflow-x-auto" role="group" aria-label="Product images">
              {images.map((src, i) => (
                <button
                  key={src}
                  type="button"
                  onClick={() => setImageIndex(i)}
                  aria-label={`Show image ${i + 1} of ${images.length}`}
                  aria-pressed={i === imageIndex}
                  className={`press h-20 w-20 shrink-0 overflow-hidden rounded-xl border-2 bg-surface transition-all duration-200 ${
                    i === imageIndex
                      ? 'border-primary ring-4 ring-primary/20'
                      : 'border-outline opacity-75 hover:-translate-y-0.5 hover:opacity-100'
                  }`}
                >
                  <ProductImage src={src} alt="" productId={`${product.id}-${i}`} />
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Buy box */}
        <div className="space-y-5 self-start rounded-[2rem] border-2 border-outline bg-surface p-5 shadow-card sm:p-7">
          <div className="space-y-2">
            {product.sellerName && (
              <p className="flex items-center gap-1.5 text-sm text-on-surface-muted">
                Sold by <span className="font-semibold text-primary">{product.sellerName}</span>
                {product.sellerVerified && (
                  <span className="inline-flex items-center gap-0.5 rounded bg-primary-container px-1.5 py-0.5 text-xs font-semibold text-on-primary-container">
                    <CheckBadgeIcon className="w-3.5 h-3.5" /> Verified
                  </span>
                )}
              </p>
            )}
            <h1 className="font-display text-2xl font-extrabold leading-tight text-on-surface sm:text-4xl">{product.name}</h1>
            <div className="flex flex-wrap items-center gap-3">
              <RatingChip rating={product.averageRating} size="md" />
              {product.ratingCount > 0 && (
                <a href="#ratings" className="text-sm text-on-surface-muted hover:text-primary">
                  {formatCount(product.ratingCount)} ratings & {formatCount(product.reviewCount)} reviews
                </a>
              )}
            </div>
          </div>

          <div className="space-y-1 border-y border-outline py-4">
            <PriceBlock
              priceAmount={product.priceAmount}
              priceCurrency={product.priceCurrency}
              listPrice={product.listPrice}
              size="lg"
            />
            {saving != null && (
              <p className="inline-flex animate-pop rounded-full bg-success/12 px-3 py-1 text-sm font-semibold text-success">
                You save {formatMoney(saving, product.priceCurrency)}
              </p>
            )}
            <StockLine quantity={product.lastKnownQuantity} />
          </div>

          {product.description && <p className="leading-relaxed text-on-surface">{product.description}</p>}

          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center rounded-full border-2 border-outline bg-surface shadow-cta" role="group" aria-label="Quantity">
              <button
                type="button"
                onClick={() => setQuantity((q) => Math.max(1, q - 1))}
                disabled={quantity <= 1}
                className="press rounded-full p-3 text-on-surface hover:bg-vivid-amber/30 disabled:opacity-40"
                aria-label="Decrease quantity"
              >
                <MinusIcon className="w-4 h-4" />
              </button>
              <span key={quantity} className="min-w-10 animate-pop text-center font-semibold" aria-live="polite">
                {quantity}
              </span>
              <button
                type="button"
                onClick={() => setQuantity((q) => Math.min(maxQuantity, q + 1))}
                disabled={quantity >= maxQuantity}
                className="press rounded-full p-3 text-on-surface hover:bg-vivid-amber/30 disabled:opacity-40"
                aria-label="Increase quantity"
              >
                <PlusIcon className="w-4 h-4" />
              </button>
            </div>
            <AddToCartButton product={product} quantity={quantity} size="lg" className="flex-1" />
            <WishlistButton productId={product.id} productName={product.name} variant="outline" />
          </div>
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Specifications">
          <SpecificationTable specifications={productDetails(product)} />
        </Card>
        <div id="ratings" className="scroll-mt-24">
          <Card title="Ratings & reviews">
            <RatingBreakdown
              averageRating={product.averageRating}
              ratingCount={product.ratingCount}
              reviewCount={product.reviewCount}
              distribution={product.ratingDistribution}
            />
          </Card>
        </div>
      </div>

      {similarProducts.length > 0 && (
        <section aria-labelledby="similar-heading" className="space-y-4">
          <h2 id="similar-heading" className="font-display text-2xl font-extrabold text-on-surface">
            Similar products
          </h2>
          <div className="grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-3 xl:grid-cols-4">
            {similarProducts.map((p) => (
              <ProductCard key={p.id} product={p} />
            ))}
          </div>
        </section>
      )}
    </div>
  )
}
