/**
 * Shared form primitives for the auth, account and checkout screens
 */

import { useId } from 'react'

export const inputClass =
  'w-full rounded-lg border-2 border-ink bg-surface px-3 py-2 text-on-surface placeholder:text-on-surface-muted focus:outline-none focus:ring-2 focus:ring-primary disabled:opacity-60'

export const primaryButtonClass =
  'press shine bg-cta-gradient inline-flex items-center justify-center gap-2 rounded-full border-2 border-ink px-5 py-2.5 font-bold text-on-accent shadow-cta disabled:cursor-not-allowed disabled:opacity-60 disabled:shadow-none'

export const secondaryButtonClass =
  'press inline-flex items-center justify-center gap-2 rounded-full border-2 border-ink bg-surface px-4 py-2 text-sm font-bold text-on-surface hover:bg-surface-variant disabled:cursor-not-allowed disabled:opacity-60'

export const cardClass = 'rounded-[1.75rem] border-2 border-outline bg-surface p-5 shadow-card'

interface FieldProps {
  label: string
  hint?: string
  children: (id: string) => React.ReactNode
}

/** Label + control + optional hint, wired together with a generated id. */
export function Field({ label, hint, children }: FieldProps) {
  const id = useId()
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-semibold text-on-surface">
        {label}
      </label>
      {children(id)}
      {hint && <p className="text-xs text-on-surface-muted">{hint}</p>}
    </div>
  )
}

export function LoadingBlock({ label = 'Loading' }: { label?: string }) {
  return (
    <div role="status" aria-label={label} className="space-y-3">
      <div className="h-6 w-1/3 rounded skeleton-loading" />
      <div className="h-24 w-full rounded-2xl skeleton-loading" />
    </div>
  )
}
