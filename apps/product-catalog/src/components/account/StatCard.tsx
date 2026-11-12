/**
 * Dashboard metric tile: colored icon badge, animated value and a caption. Becomes a link or a
 * button when given a destination or handler.
 */

import { Link } from 'react-router-dom'
import { useCountUp } from '../../hooks/useCountUp'
import type { AvatarHue } from '../../lib/account'

interface Props {
  label: string
  icon: React.ReactNode
  hue: AvatarHue
  /** Number to show; null while loading or unavailable. */
  value: number | null
  /** Formats the (animating) number, e.g. as money. Defaults to a rounded integer. */
  format?: (value: number) => string
  /** Shown instead of the number (e.g. an em dash when there is nothing to total). */
  placeholder?: string
  caption?: string
  loading?: boolean
  failed?: boolean
  to?: string
  onClick?: () => void
}

function Value({ value, format }: { value: number; format: (n: number) => string }) {
  const animated = useCountUp(value)
  return (
    <>
      {/* The counting digits are decorative; assistive tech reads only the final figure. */}
      <span aria-hidden="true">{format(animated)}</span>
      <span className="sr-only">{format(value)}</span>
    </>
  )
}

export function StatCard({
  label,
  icon,
  hue,
  value,
  format = (n) => String(Math.round(n)),
  placeholder = '—',
  caption,
  loading,
  failed,
  to,
  onClick,
}: Props) {
  const body = (
    <>
      <span
        className="flex h-11 w-11 items-center justify-center rounded-full border-2 border-ink text-white shadow-[2px_2px_0_hsl(var(--color-ink))] transition-transform duration-700 ease-[var(--ease-spring)] group-hover:scale-110 group-hover:-rotate-6 dark:text-ink"
        style={{ background: `hsl(var(--color-${hue}))` }}
        aria-hidden="true"
      >
        {icon}
      </span>
      <div className="w-full min-w-0">
        <p className="font-display truncate text-2xl leading-none text-on-surface sm:text-3xl">
          {loading ? (
            <span className="inline-block h-7 w-16 rounded skeleton-loading align-middle" aria-label="Loading" />
          ) : failed || value === null ? (
            <span title={failed ? 'Could not load' : undefined}>{placeholder}</span>
          ) : (
            <Value value={value} format={format} />
          )}
        </p>
        <p className="mt-1.5 text-sm font-bold text-on-surface">{label}</p>
        {caption && <p className="truncate text-xs text-on-surface-muted">{caption}</p>}
      </div>
    </>
  )

  const classes =
    'group press flex h-full flex-col items-start gap-3 rounded-[1.5rem] border-2 border-outline bg-surface p-4 text-left shadow-card sm:flex-row sm:items-center sm:gap-4 transition-all duration-700 hover:-translate-x-0.5 hover:-translate-y-0.5 hover:shadow-card-hover'

  if (to) {
    return (
      <Link to={to} className={classes}>
        {body}
      </Link>
    )
  }
  if (onClick) {
    return (
      <button type="button" onClick={onClick} className={`${classes} w-full`}>
        {body}
      </button>
    )
  }
  return <div className={classes}>{body}</div>
}
