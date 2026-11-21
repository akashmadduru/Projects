import type { PaymentConfig, PaymentProviderOption } from '../types'

/** Display order and wording; unknown methods are appended as sent. */
const METHOD_LABELS: [string, string][] = [
  ['upi', 'UPI'],
  ['card', 'cards'],
  ['netbanking', 'netbanking'],
  ['wallet', 'wallets'],
]

/** Providers that can take a payment in this currency (an empty currency list means any). */
export function providersFor(config: PaymentConfig | null, currency: string): PaymentProviderOption[] {
  const cur = currency.toUpperCase()
  return (config?.providers ?? []).filter((p) => p.currencies.length === 0 || p.currencies.includes(cur))
}

/**
 * Title and one-line description for the "pay online" checkout option, built from what the enabled
 * providers actually offer for this currency. Falls back to a neutral line while config is loading,
 * when it failed, or in SIMULATED mode (no providers).
 */
export function onlinePaymentSummary(config: PaymentConfig | null, currency: string): { title: string; description: string } {
  const providers = providersFor(config, currency)
  const offered = new Set(providers.flatMap((p) => p.methods.map((m) => m.toLowerCase())))
  const known = new Set(METHOD_LABELS.map(([k]) => k))
  const methods = [
    ...METHOD_LABELS.filter(([k]) => offered.has(k)).map(([, label]) => label),
    ...[...offered].filter((m) => !known.has(m)),
  ]
  if (methods.length === 0) return { title: 'Pay online', description: 'Pay securely online' }
  const list = methods.length === 1 ? (methods[0] ?? '') : `${methods.slice(0, -1).join(', ')} or ${methods[methods.length - 1]}`
  return { title: 'Pay online', description: list.charAt(0).toUpperCase() + list.slice(1) }
}
