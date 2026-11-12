/**
 * Adds a product to the cart: gradient CTA that morphs into "Added ✓" for a moment, plus a toast.
 * Never navigates when nested in a card.
 */

import { useEffect, useRef, useState } from 'react'
import { CartIcon } from './Icons'
import { effectivePrice } from '../lib/price'
import { useCartStore, useUiStore } from '../stores'
import type { Product } from '../types'

interface Props {
  product: Product
  quantity?: number
  size?: 'sm' | 'lg'
  className?: string
}

const ADDED_MS = 1400

export function AddToCartButton({ product, quantity = 1, size = 'sm', className = '' }: Props) {
  const addItem = useCartStore((s) => s.addItem)
  const notify = useUiStore((s) => s.notify)
  const [added, setAdded] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined)
  const outOfStock = product.lastKnownQuantity === 0 || !product.active

  useEffect(() => () => clearTimeout(timer.current), [])

  const onClick = (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    addItem(product.id, quantity, {
      name: product.name,
      priceAmount: effectivePrice(product),
      priceCurrency: product.priceCurrency,
      image: product.images[0],
    })
    notify(`Added ${quantity > 1 ? `${quantity} × ` : ''}${product.name} to your cart`)
    setAdded(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setAdded(false), ADDED_MS)
  }

  const sizing = size === 'lg' ? 'px-6 py-3 text-base' : 'px-3 py-2 text-sm'
  const look = added
    ? 'bg-success text-on-primary shadow-cta'
    : 'bg-cta-gradient text-on-accent shadow-cta hover:-translate-y-0.5 hover:shadow-[5px_5px_0_hsl(var(--color-ink))]'

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={outOfStock}
      className={`press shine group/btn inline-flex items-center justify-center gap-2 rounded-full border-2 border-ink font-bold disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none ${look} ${sizing} ${className}`}
    >
      {added ? (
        <span key="added" className="inline-flex items-center gap-2 animate-pop">
          <svg viewBox="0 0 24 24" className={size === 'lg' ? 'w-5 h-5' : 'w-4 h-4'} fill="none" stroke="currentColor" strokeWidth={3} aria-hidden="true">
            <path d="m5 12.5 4.5 4.5L19 7.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          Added
        </span>
      ) : (
        <>
          <CartIcon className={`transition-transform duration-700 group-hover/btn:-rotate-12 group-hover/btn:scale-110 ${size === 'lg' ? 'w-5 h-5' : 'w-4 h-4'}`} />
          {outOfStock ? 'Unavailable' : 'Add to cart'}
        </>
      )}
    </button>
  )
}
