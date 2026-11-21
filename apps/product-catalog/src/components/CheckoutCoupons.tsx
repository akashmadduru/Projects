/**
 * Checkout coupon area. Replaces an unbounded wall of coupon chips with:
 *  - the code box (always there, for codes the shopper already has),
 *  - the single best coupon as one clear call to action,
 *  - at most two more quick picks,
 *  - a "See all N coupons" dialog (searchable, sorted by saving) for everything else.
 * Once a coupon is applied it collapses to a one-line confirmation with Change / Remove.
 */

import { useCallback, useMemo, useState } from 'react'
import { formatMoney } from '../lib/format'
import type { AvailableCoupon, CouponValidation } from '../types'
import { inputClass, secondaryButtonClass } from './Form'
import { TagIcon } from './Icons'
import { Modal } from './Modal'

interface Props {
  applied: CouponValidation | null
  available: AvailableCoupon[]
  currency: string
  busy: boolean
  error: string | undefined
  /** Resolves true when the coupon was accepted. */
  onApply: (code: string) => Promise<boolean>
  onRemove: () => void
}

/** Coupons shown inline besides the dialog; the rest live behind "See all". */
const INLINE = 3

const saving = (c: AvailableCoupon): number => c.discountAmount ?? 0

function CouponCard({
  coupon,
  currency,
  best,
  busy,
  onApply,
  compact = false,
}: {
  coupon: AvailableCoupon
  currency: string
  best: boolean
  busy: boolean
  onApply: () => void
  compact?: boolean
}) {
  return (
    <div
      className={`flex items-center gap-3 rounded-xl border-2 ${best ? 'border-ink bg-vivid-amber/25 shadow-cta' : 'border-dashed border-ink bg-surface'} ${compact ? 'px-3 py-2' : 'px-3 py-2.5'}`}
    >
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="font-mono text-sm font-bold tracking-wide">{coupon.code}</span>
          {best && <span className="rounded-full bg-success px-2 py-0.5 text-[11px] font-bold text-on-primary">Best value</span>}
        </div>
        <p className="truncate text-xs text-on-surface-muted">
          {coupon.description ?? 'Discount on this order'}
          {coupon.minOrderAmount ? ` · min ${formatMoney(coupon.minOrderAmount, currency)}` : ''}
        </p>
        {saving(coupon) > 0 && (
          <p className="text-xs font-semibold text-success">You save {formatMoney(saving(coupon), currency)}</p>
        )}
      </div>
      <button
        type="button"
        disabled={busy}
        onClick={onApply}
        aria-label={`Apply coupon ${coupon.code}`}
        className="press shrink-0 rounded-full border-2 border-ink bg-accent px-3 py-1 text-xs font-bold text-on-accent shadow-cta disabled:opacity-60"
      >
        Apply
      </button>
    </div>
  )
}

export function CheckoutCoupons({ applied, available, currency, busy, error, onApply, onRemove }: Props) {
  const [input, setInput] = useState('')
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const close = useCallback(() => {
    setOpen(false)
    setQuery('')
  }, [])

  const sorted = useMemo(() => [...available].sort((a, b) => saving(b) - saving(a)), [available])
  const bestCode = sorted.length > 1 && saving(sorted[0]!) > 0 ? sorted[0]!.code : null
  const inline = sorted.slice(0, INLINE)
  const hidden = sorted.length - inline.length

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? sorted.filter((c) => `${c.code} ${c.description ?? ''}`.toLowerCase().includes(q)) : sorted
  }, [sorted, query])

  const submit = async (code: string, fromDialog = false) => {
    const ok = await onApply(code)
    if (ok) {
      setInput('')
      if (fromDialog) close()
    }
  }

  // A JSX value, not a nested component: a component defined in render would remount on every
  // keystroke and drop focus from the search box.
  const dialog = (
    <Modal
      open={open}
      onClose={close}
      title="Coupons for this order"
      subtitle={`${sorted.length} available · best savings first`}
      footer="Only one coupon applies per order. Savings are calculated on your current cart."
    >
      {sorted.length > 5 && (
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search coupons"
          aria-label="Search coupons"
          className={`${inputClass} mb-3`}
        />
      )}
      {error && (
        <p role="alert" className="mb-3 rounded-lg bg-error/10 px-3 py-2 text-xs text-error">
          {error}
        </p>
      )}
      {filtered.length === 0 ? (
        <p className="py-6 text-center text-sm text-on-surface-muted">No coupons match “{query}”.</p>
      ) : (
        <ul className="space-y-2" aria-label="All available coupons">
          {filtered.map((c) => (
            <li key={c.code}>
              <CouponCard
                coupon={c}
                currency={currency}
                best={c.code === bestCode}
                busy={busy}
                onApply={() => void submit(c.code, true)}
              />
            </li>
          ))}
        </ul>
      )}
    </Modal>
  )

  if (applied) {
    return (
      <div className="space-y-1">
        <div className="flex items-center justify-between gap-2 rounded-lg border-2 border-ink bg-primary-container px-3 py-2 text-sm text-on-primary-container">
          <span className="min-w-0">
            <span aria-hidden="true">🎉 </span>
            <span className="font-bold">{applied.code}</span> applied
            {applied.discountAmount > 0 && <span className="font-semibold"> · you save {formatMoney(applied.discountAmount, currency)}</span>}
          </span>
          <span className="flex shrink-0 gap-3">
            {sorted.length > 0 && (
              <button type="button" className="font-semibold underline" onClick={() => setOpen(true)}>
                Change
              </button>
            )}
            <button type="button" className="font-semibold underline" onClick={onRemove}>
              Remove
            </button>
          </span>
        </div>
        {dialog}
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <label className="block text-sm font-semibold text-on-surface" htmlFor="coupon-input">
        Have a coupon code?
      </label>
      <div className="flex gap-2">
        <input
          id="coupon-input"
          className={inputClass}
          value={input}
          maxLength={40}
          autoCapitalize="characters"
          placeholder="Enter code"
          onChange={(e) => setInput(e.target.value.toUpperCase())}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              void submit(input)
            }
          }}
        />
        <button type="button" className={secondaryButtonClass} disabled={busy || !input.trim()} onClick={() => void submit(input)}>
          Apply
        </button>
      </div>
      <p aria-live="polite" className="text-xs text-error">
        {open ? '' : error}
      </p>

      {sorted.length > 0 && (
        <div className="space-y-2">
          <p className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wide text-on-surface-muted">
            <TagIcon className="h-3.5 w-3.5" /> {sorted.length === 1 ? 'Available coupon' : `Available coupons (${sorted.length})`}
          </p>
          <ul className="space-y-2" aria-label="Suggested coupons">
            {inline.map((c) => (
              <li key={c.code}>
                <CouponCard coupon={c} currency={currency} best={c.code === bestCode} busy={busy} compact={c.code !== bestCode} onApply={() => void submit(c.code)} />
              </li>
            ))}
          </ul>
          {hidden > 0 && (
            <button type="button" onClick={() => setOpen(true)} aria-haspopup="dialog" className="press w-full rounded-full border-2 border-ink bg-surface px-3 py-1.5 text-sm font-bold hover:bg-surface-variant">
              See all {sorted.length} coupons
            </button>
          )}
        </div>
      )}
      {dialog}
    </div>
  )
}
