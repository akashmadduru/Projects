/**
 * Titled dashboard panel with an optional header action and the shared loading / error states
 */

import { ErrorAlert } from '../ErrorAlert'

interface Props {
  title: string
  icon?: React.ReactNode
  action?: React.ReactNode
  /** Accessible name for the region; defaults to the title. */
  id?: string
  className?: string
  children: React.ReactNode
}

export function SectionCard({ title, icon, action, id, className = '', children }: Props) {
  const headingId = id ? `${id}-heading` : undefined
  return (
    <section
      aria-labelledby={headingId}
      className={`rounded-[1.75rem] border-2 border-outline bg-surface p-5 shadow-card transition-shadow duration-700 hover:shadow-card-hover sm:p-6 ${className}`}
    >
      <header className="mb-4 flex items-center justify-between gap-3">
        <h2 id={headingId} className="font-display flex items-center gap-2 text-xl text-on-surface">
          {icon && <span className="text-accent-2">{icon}</span>}
          {title}
        </h2>
        {action}
      </header>
      {children}
    </section>
  )
}

/** Placeholder rows while a card's data loads. */
export function CardSkeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div role="status" aria-label="Loading" className="space-y-3">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="h-14 w-full rounded-2xl skeleton-loading" />
      ))}
    </div>
  )
}

/** A card-level failure with a retry, so one broken service never blanks the whole dashboard. */
export function CardError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="space-y-3">
      <ErrorAlert message={message} />
      <button
        type="button"
        onClick={onRetry}
        className="press rounded-full border-2 border-ink bg-surface px-4 py-1.5 text-sm font-bold text-on-surface hover:bg-surface-variant"
      >
        Try again
      </button>
    </div>
  )
}

export const viewAllLink =
  'group inline-flex items-center gap-1 text-sm font-bold text-primary hover:underline'
