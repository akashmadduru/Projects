const moneyFormatters = new Map<string, Intl.NumberFormat>()

export function formatMoney(amount: number, currency: string): string {
  let formatter = moneyFormatters.get(currency)
  if (!formatter) {
    formatter = new Intl.NumberFormat('en-US', { style: 'currency', currency })
    moneyFormatters.set(currency, formatter)
  }
  return formatter.format(amount)
}

const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 })

/** 1234 -> "1.2K" */
export function formatCount(value: number): string {
  return compact.format(value)
}

/** The discount worth advertising, or null when there is none to speak of. */
export function effectiveDiscount(listPrice: number | null, price: number): number | null {
  if (listPrice == null || listPrice <= price) return null
  const percent = Math.round(((listPrice - price) / listPrice) * 100)
  return percent >= 1 ? percent : null
}
