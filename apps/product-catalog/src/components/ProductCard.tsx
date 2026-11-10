/**
 * Product card component - memoized for performance with ripple effect
 */

import React, { useRef } from 'react'
import { Link } from 'react-router-dom'
import { ProductImage } from './ProductImage'
import { PriceDisplay } from './PriceDisplay'
import { StockStatus } from './StockStatus'
import type { Product } from '../types'

interface Props {
  product: Product
}

function ProductCardComponent({ product }: Props) {
  const cardRef = useRef<HTMLAnchorElement>(null)

  const handleRippleClick = (e: React.MouseEvent<HTMLAnchorElement>) => {
    const card = cardRef.current
    if (!card) return

    const rect = card.getBoundingClientRect()
    const size = Math.max(rect.width, rect.height)
    const x = e.clientX - rect.left - size / 2
    const y = e.clientY - rect.top - size / 2

    const ripple = document.createElement('div')
    ripple.className = 'ripple'
    ripple.style.width = `${size}px`
    ripple.style.height = `${size}px`
    ripple.style.left = `${x}px`
    ripple.style.top = `${y}px`

    card.appendChild(ripple)

    // Clean up ripple after animation
    setTimeout(() => {
      ripple.remove()
    }, 300)
  }

  return (
    <Link
      ref={cardRef}
      to={`/products/${product.id}`}
      onClick={handleRippleClick}
      className="block rounded-lg overflow-hidden transition-smooth hover-scale shadow-md hover:shadow-xl border border-outline/10 group animate-fade-in bg-surface hover:border-outline/20 ripple-container focus:outline-none focus:ring-2 focus:ring-outline focus:ring-offset-2"
    >
      <div className="relative w-full aspect-square bg-surface-variant overflow-hidden">
        <ProductImage
          src={product.images?.[0]}
          alt={product.name}
          productId={product.id}
        />
        <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-300" style={{background: 'linear-gradient(to top, hsl(var(--color-outline) / 0.2), transparent, transparent)'}} />
      </div>

      <div className="p-4 space-y-3">
        {product.categoryId && (
          <p className="text-xs font-medium text-on-surface-muted uppercase tracking-wide">
            {product.categoryId}
          </p>
        )}

        <h3 className="font-semibold text-on-surface line-clamp-2 text-sm group-hover:text-on-surface/80 transition-colors duration-200">
          {product.name}
        </h3>

        {product.sellerName && (
          <p className="text-xs text-on-surface-muted">
            Seller: {product.sellerName}
          </p>
        )}

        <div className="flex items-center justify-between pt-2">
          <PriceDisplay amount={product.priceAmount} currency={product.priceCurrency} size="md" />
        </div>

        {product.lastKnownQuantity !== null && (
          <StockStatus quantity={product.lastKnownQuantity} />
        )}
      </div>
    </Link>
  )
}

export const ProductCard = React.memo(ProductCardComponent)
