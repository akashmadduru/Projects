/**
 * Full-screen image viewer: focus trap, Esc to close, left/right arrows, thumbnails, wheel / button / pinch
 * zoom with drag-to-pan. Loaded lazily (React.lazy) so it stays out of the main chunk.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { ChevronRightIcon, CloseIcon, MinusIcon, PlusIcon } from './Icons'

interface Props {
  images: string[]
  index: number
  alt: string
  onIndexChange: (index: number) => void
  onClose: () => void
}

const MIN_ZOOM = 1
const MAX_ZOOM = 5

export function Lightbox({ images, index, alt, onIndexChange, onClose }: Props) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const [zoom, setZoom] = useState(1)
  const [offset, setOffset] = useState({ x: 0, y: 0 })
  const pointers = useRef(new Map<number, { x: number; y: number }>())
  const pinchStart = useRef<{ dist: number; zoom: number } | null>(null)
  const dragStart = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null)

  const clampZoom = (z: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, z))
  const resetView = useCallback(() => {
    setZoom(1)
    setOffset({ x: 0, y: 0 })
  }, [])

  const go = useCallback(
    (delta: number) => {
      resetView()
      onIndexChange((index + delta + images.length) % images.length)
    },
    [index, images.length, onIndexChange, resetView],
  )

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null
    dialogRef.current?.focus()
    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = overflow
      previouslyFocused?.focus()
    }
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (e.key === 'ArrowRight' && images.length > 1) go(1)
      else if (e.key === 'ArrowLeft' && images.length > 1) go(-1)
      else if (e.key === 'Tab') {
        // Keep focus inside the dialog.
        const focusable = dialogRef.current?.querySelectorAll<HTMLElement>('button:not([disabled])')
        if (!focusable || focusable.length === 0) return
        const first = focusable[0] as HTMLElement
        const last = focusable[focusable.length - 1] as HTMLElement
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault()
          last.focus()
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault()
          first.focus()
        }
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [go, images.length, onClose])

  const onPointerDown = (e: React.PointerEvent) => {
    e.currentTarget.setPointerCapture(e.pointerId)
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()] as [{ x: number; y: number }, { x: number; y: number }]
      pinchStart.current = { dist: Math.hypot(a.x - b.x, a.y - b.y), zoom }
    } else {
      dragStart.current = { x: e.clientX, y: e.clientY, ox: offset.x, oy: offset.y }
    }
  }
  const onPointerMove = (e: React.PointerEvent) => {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pointers.current.size === 2 && pinchStart.current) {
      const [a, b] = [...pointers.current.values()] as [{ x: number; y: number }, { x: number; y: number }]
      const dist = Math.hypot(a.x - b.x, a.y - b.y)
      setZoom(clampZoom((pinchStart.current.zoom * dist) / pinchStart.current.dist))
    } else if (dragStart.current && zoom > 1) {
      setOffset({
        x: dragStart.current.ox + e.clientX - dragStart.current.x,
        y: dragStart.current.oy + e.clientY - dragStart.current.y,
      })
    }
  }
  const onPointerUp = (e: React.PointerEvent) => {
    pointers.current.delete(e.pointerId)
    pinchStart.current = null
    dragStart.current = null
  }
  const onWheel = (e: React.WheelEvent) => {
    const next = clampZoom(zoom - e.deltaY * 0.002)
    setZoom(next)
    if (next === 1) setOffset({ x: 0, y: 0 })
  }

  const btn =
    'press flex h-11 w-11 items-center justify-center rounded-full border-2 border-ink bg-surface text-on-surface shadow-cta hover:bg-vivid-amber/30'

  return createPortal(
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-label={`${alt} image viewer`}
      tabIndex={-1}
      className="fixed inset-0 z-[70] flex flex-col bg-ink/90 focus:outline-none"
    >
      <div className="flex items-center justify-between gap-3 p-3 text-white">
        <p className="text-sm font-semibold" aria-live="polite">
          Image {index + 1} of {images.length}
        </p>
        <div className="flex items-center gap-2">
          <button type="button" className={btn} onClick={() => setZoom((z) => clampZoom(z - 0.5))} aria-label="Zoom out" disabled={zoom <= MIN_ZOOM}>
            <MinusIcon className="h-5 w-5" />
          </button>
          <button type="button" className={btn} onClick={() => setZoom((z) => clampZoom(z + 0.5))} aria-label="Zoom in" disabled={zoom >= MAX_ZOOM}>
            <PlusIcon className="h-5 w-5" />
          </button>
          <button type="button" className={btn} onClick={onClose} aria-label="Close image viewer">
            <CloseIcon className="h-5 w-5" />
          </button>
        </div>
      </div>

      <div className="relative flex min-h-0 flex-1 items-center justify-center">
        {images.length > 1 && (
          <button type="button" className={`${btn} absolute left-3 z-10 rotate-180`} onClick={() => go(-1)} aria-label="Previous image">
            <ChevronRightIcon className="h-5 w-5" />
          </button>
        )}
        <div
          className="flex h-full w-full touch-none items-center justify-center overflow-hidden"
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
          onWheel={onWheel}
          onDoubleClick={() => (zoom > 1 ? resetView() : setZoom(2.5))}
        >
          <img
            src={images[index]}
            alt={`${alt}, ${index + 1} of ${images.length}`}
            draggable={false}
            className="max-h-full max-w-full select-none object-contain"
            style={{
              transform: `translate(${offset.x}px, ${offset.y}px) scale(${zoom})`,
              cursor: zoom > 1 ? 'grab' : 'zoom-in',
              transition: pointers.current.size ? 'none' : 'transform 200ms ease-out',
            }}
          />
        </div>
        {images.length > 1 && (
          <button type="button" className={`${btn} absolute right-3 z-10`} onClick={() => go(1)} aria-label="Next image">
            <ChevronRightIcon className="h-5 w-5" />
          </button>
        )}
      </div>

      {images.length > 1 && (
        <div className="flex max-h-28 flex-wrap justify-center gap-2 overflow-y-auto p-3">
          {images.map((src, i) => (
            <button
              key={src}
              type="button"
              onClick={() => {
                resetView()
                onIndexChange(i)
              }}
              aria-label={`Show image ${i + 1}`}
              aria-current={i === index}
              className={`h-16 w-16 shrink-0 overflow-hidden rounded-lg border-2 ${i === index ? 'border-vivid-amber' : 'border-transparent opacity-70'}`}
            >
              <img src={src} alt="" className="h-full w-full object-cover" />
            </button>
          ))}
        </div>
      )}
    </div>,
    document.body,
  )
}
