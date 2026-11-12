/**
 * Active deals (Deals of the Day). One shared fetch cached for 60 s so every card reuses it.
 */

import { useEffect, useState } from 'react'
import { discoveryApi } from '../services/discoveryApi'
import type { Deal } from '../types'

const TTL_MS = 60_000
let cache: { at: number; promise: Promise<Deal[]> } | null = null

function loadDeals(): Promise<Deal[]> {
  if (!cache || Date.now() - cache.at > TTL_MS) {
    const promise = discoveryApi.activeDeals().catch(() => [] as Deal[])
    cache = { at: Date.now(), promise }
  }
  return cache.promise
}

/** As {@link useDeals}, plus whether the first fetch has settled (for skeletons). */
export function useDealsState(): { deals: Deal[]; loaded: boolean } {
  const [state, setState] = useState<{ deals: Deal[]; loaded: boolean }>({ deals: [], loaded: false })
  useEffect(() => {
    let active = true
    void loadDeals().then((d) => {
      if (active) setState({ deals: d.filter((x) => x.remaining == null || x.remaining > 0), loaded: true })
    })
    return () => {
      active = false
    }
  }, [])
  return state
}

export function useDeals(): Deal[] {
  return useDealsState().deals
}

/** Cheapest live deal for a product (variant-specific deals only match their variant). */
export function pickDeal(deals: Deal[], productId: string, variantId?: string | null): Deal | undefined {
  const now = Date.now()
  return deals
    .filter(
      (d) =>
        d.productId === productId &&
        Date.parse(d.startsAt) <= now &&
        Date.parse(d.endsAt) > now &&
        (variantId ? d.variantId == null || d.variantId === variantId : d.variantId == null),
    )
    .sort((a, b) => a.dealPrice - b.dealPrice)[0]
}

export function useDeal(productId: string, variantId?: string | null): Deal | undefined {
  const deals = useDeals()
  return pickDeal(deals, productId, variantId)
}
