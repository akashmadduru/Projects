/**
 * Listing card: image with wishlist + deal badges, rating, seller, deal price, add to cart
 */

import React from 'react'
import { Link } from 'react-router-dom'
import { AddToCartButton } from './AddToCartButton'
import { Seal } from './Atomic'
import { CheckBadgeIcon, StarIcon } from './Icons'
import { PriceBlock } from './PriceBlock'
import { ProductImage } from './ProductImage'
import { RatingChip } from './RatingChip'
import { WishlistButton } from './WishlistButton'
import { effectiveDiscount } from '../lib/format'
import type { Product } from '../types'

interface Props {
  product: Product
}

const LOW_STOCK = 10

function ProductCardComponent({ product }: Props) {
  const discount = effectiveDiscount(product.listPrice, product.priceAmount)
  const quantity = product.lastKnownQuantity
  const lowStock = quantity != null && quantity > 0 && quantity <= LOW_STOCK

  return (
    <article className="group relative flex h-full flex-col overflow-hidden rounded-[1.75rem] border-2 border-outline bg-surface shadow-card transition-all duration-450 ease-[cubic-bezier(0.34,1.56,0.64,1)] hover:-translate-x-1 hover:-translate-y-1.5 hover:shadow-card-hover has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-primary has-[a:focus-visible]:ring-offset-2 has-[a:focus-visible]:ring-offset-background">
      {/* Diner stripe that sweeps in on hover. */}
      <span
        aria-hidden="true"
        className="retro-stripes absolute inset-x-0 top-0 z-20 h-1.5 origin-left scale-x-0 transition-transform duration-700 ease-out group-hover:scale-x-100"
      />
      <div className="relative aspect-square overflow-hidden border-b-2 border-outline bg-surface-variant">
        <div aria-hidden="true" className="bg-atomic-pattern absolute inset-0" />
        <ProductImage src={product.images[0]} alt={product.name} productId={product.id} />

        <div className="pointer-events-none absolute left-3 top-3 flex flex-col items-start gap-1.5">
          {product.featured && (
            <span className="inline-flex items-center gap-1 rounded-full border-2 border-ink bg-vivid-amber px-2.5 py-0.5 text-xs font-bold text-ink shadow-[2px_2px_0_hsl(var(--color-ink))]">
              <StarIcon className="w-3 h-3" />
              Top pick
            </span>
          )}
          {discount != null && (
            <span className="relative flex h-14 w-14 items-center justify-center">
              <Seal className="absolute inset-0 h-full w-full text-deal drop-shadow-[2px_2px_0_hsl(var(--color-ink))] transition-transform duration-1000 group-hover:rotate-[60deg]" />
              <span className="font-display relative -rotate-12 text-sm text-white">-{discount}%</span>
            </span>
          )}
        </div>
        <div className="absolute right-3 top-3 z-10">
          <WishlistButton product={product} />
        </div>
      </div>

      <div className="flex flex-1 flex-col gap-2 p-4">
        <h3 className="font-body line-clamp-2 text-[15px] font-bold leading-snug text-on-surface">
          {/* The stretched link makes the whole card clickable without nesting the buttons in it. */}
          <Link
            to={`/products/${product.id}`}
            className="transition-colors duration-300 after:absolute after:inset-0 after:content-[''] focus-visible:outline-none group-hover:text-primary"
          >
            {product.name}
          </Link>
        </h3>

        <RatingChip rating={product.averageRating} count={product.ratingCount} />

        {product.sellerName && (
          <p className="flex items-center gap-1 text-xs text-on-surface-muted">
            <span className="truncate">by {product.sellerName}</span>
            {product.sellerVerified && (
              <CheckBadgeIcon className="w-3.5 h-3.5 shrink-0 text-primary" title="Verified seller" />
            )}
          </p>
        )}

        <div className="mt-auto space-y-2 pt-1">
          <PriceBlock priceAmount={product.priceAmount} priceCurrency={product.priceCurrency} listPrice={product.listPrice} />
          {lowStock && (
            <p className="flex items-center gap-1.5 text-xs font-semibold text-error">
              <span className="relative flex h-2 w-2" aria-hidden="true">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-error opacity-60" />
                <span className="relative inline-flex h-2 w-2 rounded-full bg-error" />
              </span>
              Only {quantity} left
            </p>
          )}
          {quantity === 0 && <p className="text-xs font-semibold text-error">Out of stock</p>}
          <AddToCartButton product={product} className="relative z-10 w-full" />
        </div>
      </div>
    </article>
  )
}

export const ProductCard = React.memo(ProductCardComponent)
