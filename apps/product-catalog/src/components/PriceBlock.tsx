/**
 * Deal-style price: selling price, struck-through list price, and the percentage saved
 */

import { effectiveDiscount, formatMoney } from '../lib/format'

interface Props {
  priceAmount: number
  priceCurrency: string
  listPrice: number | null
  size?: 'sm' | 'lg'
}

export function PriceBlock({ priceAmount, priceCurrency, listPrice, size = 'sm' }: Props) {
  const discount = effectiveDiscount(listPrice, priceAmount)
  const large = size === 'lg'

  return (
    <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
      <span className={`font-bold text-on-surface ${large ? 'text-3xl' : 'text-lg'}`}>
        {formatMoney(priceAmount, priceCurrency)}
      </span>
      {discount != null && listPrice != null && (
        <>
          <span className={`text-on-surface-muted line-through ${large ? 'text-base' : 'text-sm'}`}>
            <span className="sr-only">List price </span>
            {formatMoney(listPrice, priceCurrency)}
          </span>
          <span className={`font-semibold text-success ${large ? 'text-base' : 'text-sm'}`}>
            {discount}% off
          </span>
        </>
      )}
    </div>
  )
}
