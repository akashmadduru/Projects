/**
 * Native <select> styled to the storefront theme: ink-bordered pill with an offset ink shadow and the
 * tactile `press` sink, matching the filter chips and buttons. Stays a real <select> so keyboard,
 * screen readers and mobile pickers keep working; the chevron is decorative.
 */

import { useId } from 'react'

interface Option<T extends string> {
  value: T
  label: string
}

interface Props<T extends string> {
  label: string
  value: T
  options: readonly Option<T>[]
  onChange: (value: T) => void
  id?: string
  className?: string
}

export function ThemedSelect<T extends string>({ label, value, options, onChange, id, className = '' }: Props<T>) {
  const generatedId = useId()
  const selectId = id ?? generatedId

  return (
    <div className={`flex items-center gap-2 ${className}`}>
      <label
        htmlFor={selectId}
        className="whitespace-nowrap text-xs font-bold uppercase tracking-[0.18em] text-accent-2"
      >
        {label}
      </label>
      <div className="relative">
        <select
          id={selectId}
          value={value}
          onChange={(e) => onChange(e.target.value as T)}
          className="press cursor-pointer appearance-none rounded-full border-2 border-ink bg-surface py-1.5 pl-4 pr-10 text-sm font-bold text-on-surface shadow-[3px_3px_0_hsl(var(--color-ink))] hover:bg-vivid-amber/25 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-surface"
        >
          {options.map((option) => (
            <option key={option.value} value={option.value} className="bg-surface font-medium text-on-surface">
              {option.label}
            </option>
          ))}
        </select>
        <svg
          viewBox="0 0 24 24"
          aria-hidden="true"
          className="pointer-events-none absolute right-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-on-surface"
          fill="none"
          stroke="currentColor"
          strokeWidth={3}
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </div>
    </div>
  )
}
