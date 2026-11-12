/**
 * Product detail: breadcrumb, gallery (zoom + lightbox), buy box with variants, deal, offers, alerts,
 * specifications, Q&A, ratings, recommendations. Served at /p/:slug (canonical) and /products/:id (redirects).
 */

import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AddToCartButton } from '../components/AddToCartButton'
import { AlertButtons } from '../components/AlertButtons'
import { Seal, Starburst } from '../components/Atomic'
import { BoughtTogether } from '../components/BoughtTogether'
import { CompareButton } from '../components/CompareButton'
import { Countdown } from '../components/Countdown'
import { CheckBadgeIcon, ChevronRightIcon, MinusIcon, PlusIcon } from '../components/Icons'
import { OffersCard } from '../components/OffersCard'
import { OtherSellers } from '../components/OtherSellers'
import { PincodeCheck } from '../components/PincodeCheck'
import { PriceBlock } from '../components/PriceBlock'
import { ProductCard } from '../components/ProductCard'
import { ProductGallery } from '../components/ProductGallery'
import { AlsoViewedShelf, RecentlyViewedShelf } from '../components/ProductShelf'
import { RatingChip } from '../components/RatingChip'
import { ReviewsSection } from '../components/ReviewsSection'
import { ShareButton } from '../components/ShareButton'
import { SpecificationTable } from '../components/SpecificationTable'
import { isVariantAvailable, VariantPicker } from '../components/VariantPicker'
import { WishlistButton } from '../components/WishlistButton'
import { toSearchString, useCategories, useProduct, useProducts } from '../hooks'
import { useDeal } from '../hooks/useDeals'
import { useHead } from '../hooks/useHead'
import { effectiveDiscount, formatCount, formatMoney } from '../lib/format'
import { effectivePrice } from '../lib/price'
import { productPath } from '../lib/productPath'
import { pushRecentlyViewed } from '../lib/recentlyViewed'
import { discoveryApi } from '../services/discoveryApi'
import { useAuthStore } from '../stores'
import type { Product, ProductVariant } from '../types'

const QASection = lazy(() => import('../components/QASection'))
const SubscribeSave = lazy(() => import('../components/SubscribeSave'))
const AddToListMenu = lazy(() => import('../components/AddToListMenu'))

const MAX_QUANTITY = 10
const LOW_STOCK = 10

function productDetails(product: Product): Record<string, unknown> {
  const { dimensionsLength: l, dimensionsWidth: w, dimensionsHeight: h } = product
  return {
    ...(product.specifications ?? {}),
    ...(product.brand && { brand: product.brand }),
    ...(product.attributes ?? {}),
    ...(product.material && { material: product.material }),
    ...(product.color && { color: product.color }),
    ...(product.weightGrams != null && { weight: `${product.weightGrams} g` }),
    ...(l != null && w != null && h != null && { dimensions: `${l} × ${w} × ${h} cm` }),
    sku: product.sku,
  }
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-[1.75rem] border-2 border-outline bg-surface p-5 shadow-card transition-shadow duration-700 hover:shadow-card-hover sm:p-6">
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

function variantPrice(v: ProductVariant, product: Product): number {
  if (v.salePrice && v.salePrice > 0) return v.salePrice
  return v.priceAmount ?? effectivePrice(product)
}

export function ProductDetailPage() {
  const { id, slug } = useParams<{ id: string; slug: string }>()
  const navigate = useNavigate()
  const authenticated = useAuthStore((s) => s.status === 'authenticated')
  const state = useProduct(id, slug)
  const { categories } = useCategories()
  const [imageIndex, setImageIndex] = useState(0)
  const [quantity, setQuantity] = useState(1)
  const [selected, setSelected] = useState<Record<string, string>>({})

  const product = state.status === 'success' ? state.data : undefined
  const productId = product?.id
  const variants = useMemo(() => product?.variants ?? [], [product])
  const options = product?.variantOptions ?? {}
  const hasVariants = variants.length > 0 && Object.keys(options).length > 0

  // The route reuses this component when moving to another product.
  useEffect(() => {
    setImageIndex(0)
    setQuantity(1)
    const first = variants.find(isVariantAvailable) ?? variants[0]
    setSelected(first ? { ...first.attributes } : {})
  }, [productId, variants])

  // /products/:id is only an alias: move to the canonical slug URL once known.
  useEffect(() => {
    if (id && product?.slug) navigate(`/p/${product.slug}`, { replace: true })
  }, [id, product?.slug, navigate])

  // View beacon, local recently-viewed list and (signed in) server sync.
  useEffect(() => {
    if (!productId) return
    pushRecentlyViewed(productId)
    discoveryApi.trackView(productId)
    if (authenticated) discoveryApi.syncRecentlyViewed(productId)
  }, [productId, authenticated])

  const variant = hasVariants
    ? variants.find((v) => Object.keys(options).every((k) => v.attributes[k] === selected[k]))
    : undefined
  const variantMissing = hasVariants && !variant

  const deal = useDeal(product?.id ?? '', variant?.id)
  const basePrice = product ? (variant ? variantPrice(variant, product) : effectivePrice(product)) : 0
  const unitPrice = deal ? Math.min(basePrice, deal.dealPrice) : basePrice
  const variantLabel = variant ? Object.values(variant.attributes).join(' / ') : ''

  const galleryImages = useMemo(() => {
    if (!product) return []
    const extra = variants.map((v) => v.imageUrl).filter((u): u is string => !!u)
    return [...new Set([...product.images, ...extra])]
  }, [product, variants])

  // Selecting a variant with its own image switches the gallery to it.
  useEffect(() => {
    if (variant?.imageUrl) {
      const i = galleryImages.indexOf(variant.imageUrl)
      if (i >= 0) setImageIndex(i)
    }
  }, [variant?.id, variant?.imageUrl, galleryImages])

  const similar = useProducts(product?.categoryId ? { categoryId: product.categoryId } : {}, 0, 9)

  const category = categories.find((c) => c.id === product?.categoryId)
  const canonical = product ? `${window.location.origin}${productPath(product)}` : undefined
  const outOfStockNow = product
    ? variant
      ? !isVariantAvailable(variant)
      : product.lastKnownQuantity === 0 || !product.active
    : false
  const stockQuantity = variant ? variant.availableQuantity : (product?.lastKnownQuantity ?? null)

  useHead(
    product && canonical
      ? {
          title: `${product.name} | Cartwise`,
          description: product.description?.slice(0, 160) ?? `Buy ${product.name} on Cartwise`,
          canonical,
          image: product.images[0],
          type: 'product',
          jsonLd: {
            '@context': 'https://schema.org',
            '@type': 'Product',
            name: product.name,
            image: product.images,
            description: product.description ?? undefined,
            sku: variant?.sku ?? product.sku,
            ...(product.brand ? { brand: { '@type': 'Brand', name: product.brand } } : {}),
            offers: {
              '@type': 'Offer',
              url: canonical,
              price: unitPrice,
              priceCurrency: product.priceCurrency,
              availability: outOfStockNow ? 'https://schema.org/OutOfStock' : 'https://schema.org/InStock',
            },
            ...(product.ratingCount > 0 && product.averageRating != null
              ? {
                  aggregateRating: {
                    '@type': 'AggregateRating',
                    ratingValue: product.averageRating,
                    reviewCount: Math.max(product.reviewCount, 1),
                  },
                }
              : {}),
          },
        }
      : undefined,
  )

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

  const parent = categories.find((c) => c.id === category?.parentCategoryId)
  const listPrice = variant ? Math.max(product.listPrice ?? 0, variant.priceAmount ?? 0) || null : product.listPrice
  const discount = effectiveDiscount(listPrice, unitPrice)
  const saving = discount != null && listPrice != null ? Math.round((listPrice - unitPrice) * 100) / 100 : null
  const maxQuantity = Math.max(1, Math.min(MAX_QUANTITY, stockQuantity ?? MAX_QUANTITY))
  const similarProducts =
    similar.status === 'success' ? similar.data.content.filter((p) => p.id !== product.id).slice(0, 8) : []
  const buyNowDisabled = outOfStockNow || variantMissing

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
        <div className="lg:sticky lg:top-24 lg:self-start">
          <ProductGallery
            images={galleryImages}
            index={imageIndex}
            onIndexChange={setImageIndex}
            name={product.name}
            productId={product.id}
            overlay={
              <div className="flex flex-col items-start gap-2">
                {product.featured && (
                  <span className="rounded-full border-2 border-ink bg-vivid-amber px-3 py-1 text-xs font-bold text-ink shadow-[2px_2px_0_hsl(var(--color-ink))]">Top pick</span>
                )}
                {deal && (
                  <span className="rounded-full border-2 border-ink bg-deal px-3 py-1 text-xs font-bold text-white shadow-[2px_2px_0_hsl(var(--color-ink))]">
                    {deal.title || 'Deal of the day'}
                  </span>
                )}
                {discount != null && (
                  <span className="relative flex h-20 w-20 items-center justify-center">
                    <Seal className="absolute inset-0 h-full w-full text-deal drop-shadow-[3px_3px_0_hsl(var(--color-ink))]" />
                    <span className="font-display relative -rotate-12 text-lg text-white">-{discount}%</span>
                  </span>
                )}
              </div>
            }
          />
        </div>

        {/* Buy box */}
        <div className="space-y-5 self-start rounded-[2rem] border-2 border-outline bg-surface p-5 shadow-card sm:p-7">
          <div className="space-y-2">
            {product.brand && <p className="text-sm font-semibold uppercase tracking-[0.14em] text-accent-2">{product.brand}</p>}
            {(product.buyBox?.sellerName ?? product.sellerName) && (
              <p className="flex items-center gap-1.5 text-sm text-on-surface-muted">
                Sold by{' '}
                {product.buyBox?.sellerSlug ? (
                  <Link to={`/seller/${product.buyBox.sellerSlug}`} className="font-semibold text-primary hover:underline">
                    {product.buyBox.sellerName}
                  </Link>
                ) : (
                  <span className="font-semibold text-primary">{product.buyBox?.sellerName ?? product.sellerName}</span>
                )}
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
            <PriceBlock priceAmount={unitPrice} priceCurrency={product.priceCurrency} listPrice={listPrice} size="lg" />
            {saving != null && (
              <p className="inline-flex animate-pop rounded-full bg-success/12 px-3 py-1 text-sm font-semibold text-success">
                You save {formatMoney(saving, product.priceCurrency)}
              </p>
            )}
            {deal && <Countdown endsAt={deal.endsAt} className="block text-sm font-semibold text-error" />}
            {variant && <p className="text-xs text-on-surface-muted">SKU {variant.sku}</p>}
            <StockLine quantity={stockQuantity} />
          </div>

          {hasVariants && (
            <VariantPicker
              options={options}
              variants={variants}
              selected={selected}
              onSelect={(key, value) => {
                // Keep the other choices when a matching variant exists; otherwise jump to the closest one.
                const next = { ...selected, [key]: value }
                const exact = variants.find((v) => Object.entries(next).every(([k, val]) => v.attributes[k] === val))
                const fallback = variants.find((v) => v.attributes[key] === value && isVariantAvailable(v)) ?? variants.find((v) => v.attributes[key] === value)
                setSelected(exact ? next : fallback ? { ...fallback.attributes } : next)
              }}
            />
          )}

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
            <AddToCartButton
              product={product}
              quantity={quantity}
              offerId={product.buyBox?.offerId}
              size="lg"
              price={unitPrice}
              unavailable={outOfStockNow || variantMissing}
              variant={variant ? { id: variant.id, label: variantLabel, image: variant.imageUrl } : undefined}
              className="flex-1"
            />
            <WishlistButton
              product={product}
              variant="outline"
              variantId={variant?.id}
              variantLabel={variant ? variantLabel : undefined}
              variantImage={variant?.imageUrl}
            />
          </div>
          <button
            type="button"
            disabled={buyNowDisabled}
            onClick={() =>
              navigate(
                `/checkout?buyNow=${encodeURIComponent(product.id)}&qty=${quantity}${variant ? `&variant=${encodeURIComponent(variant.id)}` : ''}${product.buyBox ? `&offer=${encodeURIComponent(product.buyBox.offerId)}` : ''}`,
              )
            }
            className="press inline-flex w-full items-center justify-center rounded-full border-2 border-ink bg-vivid-amber px-6 py-3 font-bold text-ink shadow-cta disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none"
          >
            Buy now
          </button>

          <div className="flex flex-wrap items-center gap-2">
            <CompareButton product={product} />
            <ShareButton title={product.name} url={canonical ?? window.location.href} />
            <Suspense fallback={null}>
              <AddToListMenu product={product} variantId={variant?.id} variantImage={variant?.imageUrl} />
            </Suspense>
          </div>
          {!(variantMissing || outOfStockNow) && (
            <Suspense fallback={null}>
              <SubscribeSave
                productId={product.id}
                variantId={variant?.id}
                quantity={quantity}
                unitPrice={unitPrice}
                currency={product.priceCurrency}
              />
            </Suspense>
          )}
          <AlertButtons productId={product.id} variantId={variant?.id} outOfStock={outOfStockNow} currentPrice={unitPrice} currency={product.priceCurrency} productName={product.name} />

          <OtherSellers
            product={product}
            offers={product.offers ?? []}
            buyBoxId={product.buyBox?.offerId}
            quantity={quantity}
            unavailable={variantMissing}
          />

          <OffersCard productId={product.id} price={unitPrice} currency={product.priceCurrency} />
          <PincodeCheck price={unitPrice} currency={product.priceCurrency} />
        </div>
      </div>

      <BoughtTogether
        product={product}
        current={variantMissing || outOfStockNow ? null : { price: unitPrice, variant: variant ? { id: variant.id, label: variantLabel, image: variant.imageUrl } : undefined }}
      />

      <Card title="Specifications">
        <SpecificationTable specifications={productDetails(product)} />
      </Card>

      <Card title="Questions & answers">
        <Suspense fallback={<div role="status" aria-label="Loading questions" className="h-24 w-full rounded-2xl skeleton-loading" />}>
          <QASection productId={product.id} />
        </Suspense>
      </Card>

      <div id="ratings" className="scroll-mt-24">
        <div id="reviews" className="scroll-mt-24">
          <Card title="Ratings & reviews">
            <ReviewsSection product={product} />
          </Card>
        </div>
      </div>

      <AlsoViewedShelf productId={product.id} />

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

      <RecentlyViewedShelf excludeId={product.id} />
    </div>
  )
}
