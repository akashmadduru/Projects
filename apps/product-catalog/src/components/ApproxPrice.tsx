/** "≈ ₹1,496" reference price in the shopper's chosen display currency; renders nothing when native. */

import { convert, useCurrency } from '../lib/currency'
import { formatMoney } from '../lib/format'

export function ApproxPrice({ amount, currency, className = '' }: { amount: number; currency: string; className?: string }) {
  const display = useCurrency((s) => s.display)
  const fx = useCurrency((s) => s.fx)
  const converted = convert(amount, currency, fx, display)
  if (converted == null) return null
  return (
    <span className={`text-xs text-on-surface-muted ${className}`} title="Approximate. You are charged in the product currency.">
      <span aria-hidden="true">≈ </span>
      <span className="sr-only">Approximately </span>
      {formatMoney(converted, display)}
    </span>
  )
}
