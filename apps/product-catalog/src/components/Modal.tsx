/**
 * Centred modal dialog. Same behaviours as Drawer (Escape closes, focus moves in and is restored,
 * the page behind does not scroll) plus a Tab focus trap. Rendered through a portal to <body>:
 * the page content sits inside an animated (transformed) <main>, which would otherwise make
 * `position: fixed` relative to it instead of the viewport.
 */

import { useEffect, useId, useRef } from 'react'
import { createPortal } from 'react-dom'
import { CloseIcon } from './Icons'

interface Props {
  open: boolean
  onClose: () => void
  title: string
  /** One line under the title, e.g. how many offers apply. */
  subtitle?: string
  children: React.ReactNode
  footer?: React.ReactNode
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function Modal({ open, onClose, title, subtitle, children, footer }: Props) {
  const titleId = useId()
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const previouslyFocused = document.activeElement as HTMLElement | null
    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'
    panelRef.current?.focus()

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onClose()
        return
      }
      if (e.key !== 'Tab' || !panelRef.current) return
      const items = [...panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)]
      if (items.length === 0) {
        e.preventDefault()
        return
      }
      const first = items[0]!
      const last = items[items.length - 1]!
      const active = document.activeElement
      if (e.shiftKey && (active === first || active === panelRef.current)) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && active === last) {
        e.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = overflow
      previouslyFocused?.focus()
    }
  }, [open, onClose])

  if (!open) return null

  return createPortal(
    <div className="fixed inset-0 z-[70] flex items-end justify-center sm:items-center sm:p-4">
      <div className="animate-fade-in absolute inset-0 bg-brand/50 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="animate-pop relative flex max-h-[88vh] w-full max-w-lg flex-col overflow-hidden rounded-t-[2rem] border-2 border-ink bg-surface shadow-card-hover focus:outline-none sm:rounded-[2rem]"
      >
        <div className="bg-brand-gradient flex items-start justify-between gap-3 border-b-2 border-ink px-5 py-4 text-on-brand">
          <div className="min-w-0">
            <h2 id={titleId} className="text-lg font-bold">
              {title}
            </h2>
            {subtitle && <p className="text-xs opacity-80">{subtitle}</p>}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="press shrink-0 rounded-xl p-2 hover:rotate-90 hover:bg-on-brand/15"
            aria-label="Close"
          >
            <CloseIcon />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="border-t border-outline bg-surface-variant/40 px-5 py-3 text-xs text-on-surface-muted">{footer}</div>}
      </div>
    </div>,
    document.body,
  )
}
