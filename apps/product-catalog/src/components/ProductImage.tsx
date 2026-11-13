/**
 * ProductImage component with lazy loading, skeleton, gradient placeholder, and retry on error
 */

import React, { useState } from 'react'

interface Props {
  src?: string | null
  alt: string
  productId: string
  /** Intrinsic size hint (reserves layout space, avoids CLS). The box is still sized by CSS. */
  width?: number
  height?: number
  /** `sizes` attribute for the generated srcset. */
  sizes?: string
}

const WIDTHS = [240, 400, 640, 800]

/** URL of the same image at a given width, or null when the host has no known resize parameter. */
function resized(src: string, width: number): string | null {
  try {
    const url = new URL(src, window.location.href)
    if (url.hostname === 'images.unsplash.com') {
      url.searchParams.set('w', String(width))
      url.searchParams.delete('h') // keep the natural aspect ratio at every width
      return url.toString()
    }
    const picsum = /^\/(\d+)\/(\d+)$/.exec(url.pathname)
    if (url.hostname === 'picsum.photos' && picsum) {
      const ratio = Number(picsum[2]) / Number(picsum[1])
      url.pathname = `/${width}/${Math.round(width * ratio)}`
      return url.toString()
    }
  } catch {
    // Not a parseable URL: serve as-is.
  }
  return null
}

function buildSrcSet(src: string): string | undefined {
  const entries = WIDTHS.map((w) => {
    const u = resized(src, w)
    return u ? `${u} ${w}w` : null
  }).filter((e): e is string => e !== null)
  return entries.length > 0 ? entries.join(', ') : undefined
}

type Tone = { token: 'outline' | 'on-surface'; from: number; to: number }

const TONES: Tone[] = [
  { token: 'outline', from: 0.8, to: 0.4 },
  { token: 'on-surface', from: 0.8, to: 0.4 },
  { token: 'outline', from: 0.9, to: 0.3 },
  { token: 'on-surface', from: 0.7, to: 0.35 },
  { token: 'outline', from: 0.85, to: 0.35 },
  { token: 'on-surface', from: 0.75, to: 0.3 },
  { token: 'outline', from: 0.95, to: 0.45 },
  { token: 'on-surface', from: 0.85, to: 0.4 },
]

/** Deterministic per-product gradient, built from theme tokens so it follows light/dark mode. */
function gradient(productId: string, strength: number): string {
  const hash = productId.split('').reduce((acc, char) => (acc << 5) - acc + char.charCodeAt(0), 0)
  const tone = TONES[Math.abs(hash) % TONES.length] ?? TONES[0]!
  const color = (alpha: number) => `hsl(var(--color-${tone.token}) / ${(alpha * strength).toFixed(3)})`
  return `linear-gradient(135deg, ${color(tone.from)} 0%, ${color(tone.to)} 100%)`
}

// A plain `src + '?retry=n'` breaks URLs that already carry a query string (e.g. `?w=400`).
function withRetryParam(src: string, attempt: number): string {
  if (attempt === 0) return src
  try {
    const url = new URL(src, window.location.href)
    url.searchParams.set('retry', String(attempt))
    return url.toString()
  } catch {
    return src
  }
}

function PlaceholderIcon() {
  return (
    <svg className="w-16 h-16 opacity-60" fill="currentColor" viewBox="0 0 20 20" aria-hidden="true">
      <path d="M4 3a2 2 0 00-2 2v10a2 2 0 002 2h12a2 2 0 002-2V5a2 2 0 00-2-2H4zm12 12H4l4-8 3 6 2-4 3 6z" />
    </svg>
  )
}

function ProductImageComponent({ src, alt, productId, width = 400, height = 400, sizes = '(min-width: 1024px) 25vw, (min-width: 640px) 33vw, 50vw' }: Props) {
  const [status, setStatus] = useState<'loading' | 'loaded' | 'error'>('loading')
  const [attempt, setAttempt] = useState(0)
  const [trackedSrc, setTrackedSrc] = useState(src)

  // A new src (e.g. list thumbnail -> detail image) must not inherit the previous one's state.
  if (src !== trackedSrc) {
    setTrackedSrc(src)
    setStatus('loading')
    setAttempt(0)
  }

  if (!src) {
    return (
      <div
        className="w-full h-full flex items-center justify-center text-muted"
        style={{ background: gradient(productId, 0.15) }}
        role="img"
        aria-label={alt}
      >
        <PlaceholderIcon />
      </div>
    )
  }

  if (status === 'error') {
    const handleRetry = (e: React.MouseEvent<HTMLButtonElement>) => {
      // The card wraps this image in a <Link>; retrying must not navigate.
      e.preventDefault()
      e.stopPropagation()
      setAttempt((n) => n + 1)
      setStatus('loading')
    }

    return (
      <div
        className="w-full h-full flex flex-col items-center justify-center text-muted gap-3 relative overflow-hidden group"
        style={{ background: gradient(productId, 0.19) }}
        role="img"
        aria-label={alt}
      >
        <div
          className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-700"
          style={{ background: gradient(productId, 0.31) }}
        />
        <div className="relative z-10 flex flex-col items-center">
          <PlaceholderIcon />
          <button
            type="button"
            onClick={handleRetry}
            className="mt-3 px-3 py-1 bg-surface/60 backdrop-blur-sm text-xs font-medium rounded-full hover:bg-surface/80 transition-all duration-500 border border-outline/50 hover:border-outline"
            aria-label="Retry loading image"
          >
            Retry
          </button>
        </div>
      </div>
    )
  }

  const srcSet = attempt === 0 ? buildSrcSet(src) : undefined
  const lqip = status === 'loading' ? resized(src, 24) : null

  return (
    <div className="w-full h-full relative">
      {lqip && <img src={lqip} alt="" aria-hidden="true" className="absolute inset-0 h-full w-full scale-110 object-cover blur-lg" />}
      <img
        src={withRetryParam(src, attempt)}
        {...(srcSet ? { srcSet, sizes } : {})}
        width={width}
        height={height}
        alt={alt}
        onLoad={() => setStatus('loaded')}
        onError={() => setStatus('error')}
        className={`w-full h-full object-cover transition-[transform,opacity] duration-1000 ease-[var(--ease-smooth)] group-hover:scale-110 ${
          status === 'loaded' ? 'opacity-100' : 'opacity-0'
        }`}
        loading="lazy"
        decoding="async"
      />

      {status === 'loading' && (
        <div
          className="absolute inset-0 w-full h-full animate-pulse"
          style={{ background: gradient(productId, 0.125) }}
          aria-hidden="true"
        >
          <div className="absolute inset-0 animate-shimmer bg-gradient-to-r from-transparent via-on-surface/10 to-transparent" />
        </div>
      )}
    </div>
  )
}

export const ProductImage = React.memo(ProductImageComponent)
