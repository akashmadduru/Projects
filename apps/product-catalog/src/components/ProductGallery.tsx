/**
 * PDP gallery: hover zoom on desktop, click to open the lazy lightbox, thumbnail strip.
 * The parent controls the active index so a variant's image can be switched in.
 */

import { lazy, Suspense, useState } from 'react'
import { ProductImage } from './ProductImage'

const Lightbox = lazy(() => import('./Lightbox').then((m) => ({ default: m.Lightbox })))

interface Props {
  images: string[]
  index: number
  onIndexChange: (index: number) => void
  name: string
  productId: string
  /** Badges (top pick, discount seal) drawn over the main image. */
  overlay?: React.ReactNode
}

export function ProductGallery({ images, index, onIndexChange, name, productId, overlay }: Props) {
  const [open, setOpen] = useState(false)
  const [origin, setOrigin] = useState<{ x: number; y: number } | null>(null)
  const active = images[Math.min(index, images.length - 1)]

  return (
    <div className="space-y-3">
      <div
        className="group relative aspect-square overflow-hidden rounded-[2rem] border-2 border-outline bg-surface-variant shadow-card"
        onMouseMove={(e) => {
          if (!window.matchMedia('(hover: hover) and (pointer: fine)').matches) return
          const rect = e.currentTarget.getBoundingClientRect()
          setOrigin({ x: ((e.clientX - rect.left) / rect.width) * 100, y: ((e.clientY - rect.top) / rect.height) * 100 })
        }}
        onMouseLeave={() => setOrigin(null)}
      >
        <div
          className="h-full w-full transition-transform duration-300 ease-out"
          style={origin ? { transform: 'scale(1.8)', transformOrigin: `${origin.x}% ${origin.y}%` } : undefined}
        >
          <ProductImage src={active} alt={name} productId={productId} />
        </div>
        {active && (
          <button
            type="button"
            onClick={() => setOpen(true)}
            aria-label={`Open ${name} images full screen`}
            className="absolute inset-0 cursor-zoom-in focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-primary/40"
          />
        )}
        <div className="pointer-events-none absolute left-4 top-4">{overlay}</div>
      </div>

      {images.length > 1 && (
        <div className="flex flex-wrap gap-2" role="group" aria-label="Product images">
          {images.map((src, i) => (
            <button
              key={src}
              type="button"
              onClick={() => onIndexChange(i)}
              aria-label={`Show image ${i + 1} of ${images.length}`}
              aria-pressed={i === index}
              className={`press h-20 w-20 shrink-0 overflow-hidden rounded-xl border-2 bg-surface transition-all duration-500 ${
                i === index
                  ? 'border-primary ring-4 ring-primary/20'
                  : 'border-outline opacity-75 hover:-translate-y-0.5 hover:opacity-100'
              }`}
            >
              <ProductImage src={src} alt="" productId={`${productId}-${i}`} />
            </button>
          ))}
        </div>
      )}

      {open && (
        <Suspense fallback={null}>
          <Lightbox images={images} index={index} alt={name} onIndexChange={onIndexChange} onClose={() => setOpen(false)} />
        </Suspense>
      )}
    </div>
  )
}
