/**
 * ProductImage component with intelligent loading, fallbacks, and error handling
 * Features: lazy loading, skeleton loader, gradient placeholder, retry on error
 */

import React, { useState, useEffect, useRef, useCallback } from 'react'

interface Props {
  src?: string
  alt: string
  productId: string
}

/**
 * Generate a hash-based gradient color pair for consistent product colors
 * Each product gets a unique, deterministic gradient based on its ID
 * Using semantic CSS variables that adapt to light/dark theme
 */
function getGradientColors(productId: string): [string, string] {
  const hash = productId.split('').reduce((acc, char) => {
    return ((acc << 5) - acc) + char.charCodeAt(0)
  }, 0)

  // Use CSS color variables with different opacity levels for variation
  // All colors derive from CSS variables for perfect theme consistency
  const colors = [
    { from: 'hsl(var(--color-outline) / 0.8)', to: 'hsl(var(--color-outline) / 0.4)' },
    { from: 'hsl(var(--color-on-surface) / 0.8)', to: 'hsl(var(--color-on-surface) / 0.4)' },
    { from: 'hsl(var(--color-outline) / 0.9)', to: 'hsl(var(--color-outline) / 0.3)' },
    { from: 'hsl(var(--color-on-surface) / 0.7)', to: 'hsl(var(--color-on-surface) / 0.35)' },
    { from: 'hsl(var(--color-outline) / 0.85)', to: 'hsl(var(--color-outline) / 0.35)' },
    { from: 'hsl(var(--color-on-surface) / 0.75)', to: 'hsl(var(--color-on-surface) / 0.3)' },
    { from: 'hsl(var(--color-outline) / 0.95)', to: 'hsl(var(--color-outline) / 0.45)' },
    { from: 'hsl(var(--color-on-surface) / 0.85)', to: 'hsl(var(--color-on-surface) / 0.4)' },
  ]

  const color = colors[Math.abs(hash) % colors.length]
  return color ? [color.from, color.to] : ['hsl(var(--color-outline) / 0.8)', 'hsl(var(--color-outline) / 0.4)']
}

/**
 * SVG placeholder icon for fallback state
 */
function PlaceholderIcon() {
  return (
    <svg
      className="w-16 h-16 opacity-60"
      fill="currentColor"
      viewBox="0 0 20 20"
      aria-hidden="true"
    >
      <path d="M4 3a2 2 0 00-2 2v10a2 2 0 002 2h12a2 2 0 002-2V5a2 2 0 00-2-2H4zm12 12H4l4-8 3 6 2-4 3 6z" />
    </svg>
  )
}

function ProductImageComponent({ src, alt, productId }: Props) {
  const [imageState, setImageState] = useState<'loading' | 'loaded' | 'error'>('loading')
  const [isInView, setIsInView] = useState(!src) // Load immediately if no src
  const [retryCount, setRetryCount] = useState(0)
  const imageRef = useRef<HTMLImageElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  const [gradientFrom, gradientTo] = getGradientColors(productId)

  // Setup intersection observer for lazy loading
  useEffect(() => {
    if (!src || isInView) return

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            setIsInView(true)
            observer.unobserve(entry.target)
          }
        })
      },
      {
        rootMargin: '50px',
      }
    )

    if (containerRef.current) {
      observer.observe(containerRef.current)
    }

    return () => observer.disconnect()
  }, [src, isInView])

  const handleImageLoad = useCallback(() => {
    setImageState('loaded')
    setRetryCount(0)
  }, [])

  const handleImageError = useCallback(() => {
    setImageState('error')
  }, [])

  const handleRetry = useCallback(() => {
    setRetryCount((prev) => prev + 1)
    setImageState('loading')
    if (imageRef.current) {
      imageRef.current.src = src + (retryCount > 0 ? `?retry=${retryCount}` : '')
    }
  }, [src, retryCount])

  // Error state: fallback with retry button
  if (imageState === 'error') {
    return (
      <div
        ref={containerRef}
        className="w-full h-full flex flex-col items-center justify-center text-muted gap-3 relative overflow-hidden group"
        style={{
          background: `linear-gradient(135deg, ${gradientFrom}30 0%, ${gradientTo}30 100%)`,
        }}
      >
        <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-300"
          style={{
            background: `linear-gradient(135deg, ${gradientFrom}50 0%, ${gradientTo}50 100%)`,
          }}
        />
        <div className="relative z-10">
          <PlaceholderIcon />
          <button
            onClick={handleRetry}
            className="mt-3 px-3 py-1 bg-surface/60 backdrop-blur-sm text-xs font-medium rounded-full hover:bg-surface/80 transition-all duration-200 border border-outline/50 hover:border-outline"
            aria-label="Retry loading image"
          >
            Retry
          </button>
        </div>
      </div>
    )
  }

  // Fallback state: when no image source is provided
  if (!src && !isInView) {
    return (
      <div
        ref={containerRef}
        className="w-full h-full flex items-center justify-center text-muted relative overflow-hidden"
        style={{
          background: `linear-gradient(135deg, ${gradientFrom}25 0%, ${gradientTo}25 100%)`,
        }}
      >
        <PlaceholderIcon />
      </div>
    )
  }

  // Fallback state: no image URL
  if (!src) {
    return (
      <div
        className="w-full h-full flex items-center justify-center text-muted"
        style={{
          background: `linear-gradient(135deg, ${gradientFrom}25 0%, ${gradientTo}25 100%)`,
        }}
      >
        <PlaceholderIcon />
      </div>
    )
  }

  // Loading or loaded state: show image with optional skeleton overlay
  // FIXED: Now renders img element even while loading so image can actually load
  return (
    <div ref={containerRef} className="w-full h-full relative">
      <img
        ref={imageRef}
        src={isInView ? src : undefined}
        alt={alt}
        onLoad={handleImageLoad}
        onError={handleImageError}
        className={`w-full h-full object-cover transition-transform duration-300 group-hover:scale-110 ${
          imageState === 'loaded' ? 'opacity-100' : 'opacity-0'
        }`}
        loading="lazy"
        decoding="async"
      />
      
      {/* Skeleton loader overlay while image is loading */}
      {imageState === 'loading' && isInView && (
        <div
          className="absolute inset-0 w-full h-full bg-surface-variant/50 animate-pulse flex items-center justify-center"
          style={{
            background: `linear-gradient(135deg, ${gradientFrom}20 0%, ${gradientTo}20 100%)`,
          }}
        >
          <div className="absolute inset-0 animate-shimmer bg-gradient-to-r from-transparent via-on-surface/10 to-transparent" />
        </div>
      )}
    </div>
  )
}

export const ProductImage = React.memo(ProductImageComponent)
