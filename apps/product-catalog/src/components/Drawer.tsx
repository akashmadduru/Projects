/**
 * Side sheet with modal-dialog behaviour: Escape closes, focus moves in and is restored, the page
 * behind does not scroll. Animates both in and out.
 */

import { useEffect, useId, useRef, useState } from 'react'
import { CloseIcon } from './Icons'

interface Props {
  open: boolean
  onClose: () => void
  title: string
  side?: 'left' | 'right'
  children: React.ReactNode
  footer?: React.ReactNode
}

// Must match the slide-out / fade-out durations in style.css (500ms).
const EXIT_MS = 500

export function Drawer({ open, onClose, title, side = 'right', children, footer }: Props) {
  const titleId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  // Stays true for the length of the exit animation after `open` turns false.
  const [rendered, setRendered] = useState(open)

  useEffect(() => {
    if (open) {
      setRendered(true)
      return
    }
    const timer = setTimeout(() => setRendered(false), EXIT_MS)
    return () => clearTimeout(timer)
  }, [open])

  useEffect(() => {
    if (!open) return
    const previouslyFocused = document.activeElement as HTMLElement | null
    panelRef.current?.focus()
    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = overflow
      previouslyFocused?.focus()
    }
  }, [open, onClose])

  if (!open && !rendered) return null

  const enter = side === 'right' ? 'animate-slide-in-right' : 'animate-slide-in-left'
  const exit = side === 'right' ? 'animate-slide-out-right' : 'animate-slide-out-left'
  return (
    <div className="fixed inset-0 z-[60]" aria-hidden={!open}>
      <div
        className={`absolute inset-0 bg-brand/40 backdrop-blur-sm ${open ? 'animate-fade-in' : 'animate-fade-out'}`}
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`absolute top-0 ${side === 'right' ? 'right-0 rounded-l-[2rem] border-l-2' : 'left-0 rounded-r-[2rem] border-r-2'} border-ink ${
          open ? enter : exit
        } flex h-full w-full max-w-md flex-col overflow-hidden bg-surface shadow-card-hover focus:outline-none`}
      >
        <div className="bg-brand-gradient flex items-center justify-between border-b-2 border-ink px-5 py-4 text-on-brand">
          <h2 id={titleId} className="text-lg font-bold">
            {title}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="press rounded-xl p-2 hover:rotate-90 hover:bg-on-brand/15"
            aria-label="Close"
          >
            <CloseIcon />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="border-t border-outline px-5 py-4">{footer}</div>}
      </div>
    </div>
  )
}
