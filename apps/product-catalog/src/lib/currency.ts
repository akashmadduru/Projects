/** Display-currency conversion for reference prices. Checkout always charges in the product currency. */

import { useEffect } from 'react'
import { create } from 'zustand'
import { apiFetch } from './http'

export interface FxRates {
  base: string
  rates: Record<string, number>
  asOf?: string
}

const KEY = 'display-currency'
export const DISPLAY_CURRENCIES = ['NATIVE', 'INR', 'USD', 'EUR'] as const

interface CurrencyState {
  display: string
  fx: FxRates | null
  setDisplay: (code: string) => void
  load: () => void
}

let loading = false

export const useCurrency = create<CurrencyState>()((set, get) => ({
  display: (() => {
    try {
      return localStorage.getItem(KEY) ?? 'NATIVE'
    } catch {
      return 'NATIVE'
    }
  })(),
  fx: null,
  setDisplay: (code) => {
    try {
      localStorage.setItem(KEY, code)
    } catch {
      // Preference will not persist.
    }
    set({ display: code })
    if (code !== 'NATIVE') get().load()
  },
  load: () => {
    if (get().fx || loading) return
    loading = true
    apiFetch<FxRates>('/api/products/fx-rates')
      .then((fx) => set({ fx }))
      .catch(() => undefined)
      .finally(() => {
        loading = false
      })
  },
}))

/** Converted amount in the display currency, or null when no conversion applies/is possible. */
export function convert(amount: number, from: string, fx: FxRates | null, to: string): number | null {
  if (to === 'NATIVE' || to === from || !fx) return null
  const rf = fx.rates[from]
  const rt = fx.rates[to]
  if (!rf || !rt) return null
  return (amount / rf) * rt
}

/** Loads the rates once a non-native display currency is selected. */
export function useFxLoader(): void {
  const display = useCurrency((s) => s.display)
  const load = useCurrency((s) => s.load)
  useEffect(() => {
    if (display !== 'NATIVE') load()
  }, [display, load])
}
