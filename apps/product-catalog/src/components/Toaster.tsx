/**
 * Bottom-centre toast stack with a countdown bar, announced politely to screen readers
 */

import { CloseIcon } from './Icons'
import { TOAST_MS, useUiStore } from '../stores'

export function Toaster() {
  const toasts = useUiStore((s) => s.toasts)
  const dismiss = useUiStore((s) => s.dismiss)

  return (
    <div
      aria-live="polite"
      className="pointer-events-none fixed inset-x-0 bottom-4 z-[70] flex flex-col items-center gap-2 px-4"
    >
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className="animate-slide-up pointer-events-auto relative flex max-w-md items-center gap-3 overflow-hidden rounded-full border-2 border-outline bg-surface py-2.5 pl-2.5 pr-4 text-sm text-on-surface shadow-card"
        >
          <span className="bg-brand-gradient flex h-8 w-8 shrink-0 items-center justify-center rounded-full border-2 border-ink text-on-brand">
            <svg viewBox="0 0 24 24" className="w-4 h-4 animate-pop" fill="none" stroke="currentColor" strokeWidth={3} aria-hidden="true">
              <path d="m5 12.5 4.5 4.5L19 7.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
          <span className="flex-1 font-medium">{toast.message}</span>
          <button
            type="button"
            onClick={() => dismiss(toast.id)}
            className="press rounded-lg p-1 text-on-surface-muted hover:bg-surface-variant hover:text-on-surface"
            aria-label="Dismiss notification"
          >
            <CloseIcon className="w-4 h-4" />
          </button>
          <span
            aria-hidden="true"
            className="bg-cta-gradient animate-toast-countdown absolute inset-x-0 bottom-0 h-1"
            style={{ animationDuration: `${TOAST_MS}ms` }}
          />
        </div>
      ))}
    </div>
  )
}
