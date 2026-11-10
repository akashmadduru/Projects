/**
 * Related products carousel component
 */

import { useState, useRef } from 'react'
import { ProductCard } from './ProductCard'
import type { Product } from '../types'

interface Props {
  products: Product[]
  title?: string
}

export function RelatedProducts({ products, title = 'Related Products' }: Props) {
  const [scrollPosition, setScrollPosition] = useState(0)
  const containerRef = useRef<HTMLDivElement>(null)

  if (products.length === 0) {
    return null
  }

  const scroll = (direction: 'left' | 'right') => {
    if (!containerRef.current) return

    const scrollAmount = 320
    const newPosition = direction === 'left' ? scrollPosition - scrollAmount : scrollPosition + scrollAmount

    containerRef.current.scrollTo({
      left: newPosition,
      behavior: 'smooth',
    })
    setScrollPosition(newPosition)
  }

  const canScrollLeft = scrollPosition > 0
  const canScrollRight = containerRef.current
    ? scrollPosition < containerRef.current.scrollWidth - containerRef.current.clientWidth - 50
    : true

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold text-on-surface">{title}</h2>
        {products.length > 4 && (
          <div className="flex gap-2">
            <button
              onClick={() => scroll('left')}
              disabled={!canScrollLeft}
              className="p-2 rounded-lg border border-outline/20 text-on-surface hover:bg-surface-variant disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
              </svg>
            </button>
            <button
              onClick={() => scroll('right')}
              disabled={!canScrollRight}
              className="p-2 rounded-lg border border-outline/20 text-on-surface hover:bg-surface-variant disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </button>
          </div>
        )}
      </div>

      <div
        ref={containerRef}
        className="flex gap-6 overflow-x-auto scroll-smooth pb-2"
        style={{ scrollBehavior: 'smooth' }}
      >
        {products.map((product, index) => (
          <div
            key={product.id}
            style={{
              animation: `slideUpStagger 0.6s ease-out ${index * 0.05}s both`,
              minWidth: 'min(100%, 280px)',
              flexShrink: 0,
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
