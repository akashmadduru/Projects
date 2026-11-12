/**
 * Paged carousel: replaces native horizontal-scroll rows. No scrollbar; arrow buttons, page dots,
 * swipe (touch/pen/mouse drag) and ←/→ keys move one page at a time. The number of cards per
 * page follows the container width (`itemMinWidth`), and cards that are off-screen are `inert`
 * so keyboard focus never lands on something you cannot see. Shows no controls if everything fits.
 */

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { ChevronRightIcon } from './Icons'

interface CarouselProps {
  /** Accessible name of the region, e.g. "Deals of the day". */
  label: string
  children: ReactNode[]
  /** Minimum card width in px; more cards per page appear as the container grows. */
  itemMinWidth?: number
  gap?: number
  className?: string
}

const SWIPE_PX = 40

export function Carousel({ label, children, itemMinWidth = 240, gap = 16, className = '' }: CarouselProps) {
  const viewport = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [page, setPage] = useState(0)
  const [drag, setDrag] = useState(0)
  const start = useRef<{ x: number; id: number } | null>(null)
  const moved = useRef(false)

  const count = children.length
  const perView = Math.max(1, Math.floor((width + gap) / (itemMinWidth + gap)))
  const itemW = width > 0 ? (width - gap * (perView - 1)) / perView : itemMinWidth
  const pages = Math.max(1, Math.ceil(count / perView))
  const step = perView * (itemW + gap)
  const maxOffset = Math.max(0, count * (itemW + gap) - gap - width)
  const safePage = Math.min(page, pages - 1)
  const offset = Math.min(safePage * step, maxOffset)

  useLayoutEffect(() => {
    const el = viewport.current
    if (!el) return
    setWidth(el.clientWidth)
    const ro = new ResizeObserver(() => setWidth(el.clientWidth))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    if (page > pages - 1) setPage(pages - 1)
  }, [page, pages])

  const go = useCallback((delta: number) => setPage((p) => Math.max(0, Math.min(pages - 1, p + delta))), [pages])

  const onPointerDown = (e: React.PointerEvent) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return
    start.current = { x: e.clientX, id: e.pointerId }
    moved.current = false
  }
  const onPointerMove = (e: React.PointerEvent) => {
    if (!start.current || start.current.id !== e.pointerId) return
    const dx = e.clientX - start.current.x
    if (Math.abs(dx) > 6) {
      moved.current = true
      viewport.current?.setPointerCapture(e.pointerId)
    }
    if (moved.current) setDrag(dx)
  }
  const onPointerEnd = (e: React.PointerEvent) => {
    if (!start.current || start.current.id !== e.pointerId) return
    const dx = e.clientX - start.current.x
    start.current = null
    setDrag(0)
    if (Math.abs(dx) > SWIPE_PX) go(dx < 0 ? 1 : -1)
  }
  // A drag must not count as a click on the card underneath.
  const onClickCapture = (e: React.MouseEvent) => {
    if (moved.current) {
      e.preventDefault()
      e.stopPropagation()
      moved.current = false
    }
  }

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowRight') go(1)
    else if (e.key === 'ArrowLeft') go(-1)
  }

  const multi = pages > 1
  const arrow =
    'press absolute top-1/2 z-10 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full border-2 border-ink bg-surface text-on-surface shadow-cta transition-opacity duration-500 disabled:pointer-events-none disabled:opacity-0'

  return (
    <div
      role="region"
      aria-roledescription="carousel"
      aria-label={label}
      className={`relative ${className}`}
      onKeyDown={onKeyDown}
    >
      <div
        ref={viewport}
        className="overflow-hidden py-2 [touch-action:pan-y]"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerEnd}
        onPointerCancel={onPointerEnd}
        onClickCapture={onClickCapture}
      >
        <div
          className="flex"
          style={{
            gap,
            transform: `translateX(${-offset + drag}px)`,
            transition: drag ? 'none' : 'transform 600ms var(--ease-glide, ease)',
          }}
        >
          {children.map((child, i) => {
            const left = i * (itemW + gap)
            const visible = left >= offset - 2 && left + itemW <= offset + width + 2
            return (
              <div
                key={i}
                style={{ width: itemW, flex: '0 0 auto' }}
                inert={!visible}
                aria-roledescription="slide"
                aria-label={`${i + 1} of ${count}`}
              >
                {child}
              </div>
            )
          })}
        </div>
      </div>

      {multi && (
        <>
          <button
            type="button"
            aria-label="Previous"
            disabled={safePage === 0}
            onClick={() => go(-1)}
            className={`${arrow} -left-3 sm:-left-5`}
          >
            <ChevronRightIcon className="h-5 w-5 rotate-180" />
          </button>
          <button
            type="button"
            aria-label="Next"
            disabled={safePage >= pages - 1}
            onClick={() => go(1)}
            className={`${arrow} -right-3 sm:-right-5`}
          >
            <ChevronRightIcon className="h-5 w-5" />
          </button>
          <div className="mt-3 flex justify-center gap-2" role="group" aria-label="Choose page">
            {Array.from({ length: pages }, (_, i) => (
              <button
                key={i}
                type="button"
                aria-label={`Page ${i + 1} of ${pages}`}
                aria-current={i === safePage}
                onClick={() => setPage(i)}
                className={`h-2.5 rounded-full border-2 border-ink transition-all duration-500 ${
                  i === safePage ? 'w-7 bg-vivid-amber' : 'w-2.5 bg-surface-variant hover:bg-vivid-amber/50'
                }`}
              />
            ))}
          </div>
        </>
      )}
    </div>
  )
}
