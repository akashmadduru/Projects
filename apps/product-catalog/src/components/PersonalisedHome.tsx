/**
 * Home sections for signed-in or returning visitors: continue where you left off, recommended for you,
 * buy again, and deals in categories you looked at. Renders nothing for a first-time anonymous visit.
 * Each section hides itself when empty or when its backend call fails. Lazy-loaded from the home page.
 */

import { useEffect, useState } from 'react'
import { Shelf } from './ProductShelf'
import { getRecentlyViewed } from '../lib/recentlyViewed'
import { discoveryApi } from '../services/discoveryApi'
import { engageApi } from '../services/engageApi'
import { useAuthStore } from '../stores'
import type { Deal, Product } from '../types'

interface Sections {
  recent: Product[]
  recommended: Product[]
  buyAgain: Product[]
  dealsForYou: Product[]
}

const EMPTY: Sections = { recent: [], recommended: [], buyAgain: [], dealsForYou: [] }

async function settled<T>(p: Promise<T>, fallback: T): Promise<T> {
  try {
    return await p
  } catch {
    return fallback
  }
}

export default function PersonalisedHome() {
  const authenticated = useAuthStore((s) => s.status === 'authenticated')
  const [sections, setSections] = useState<Sections>(EMPTY)

  useEffect(() => {
    let live = true
    const viewedIds = getRecentlyViewed()

    void (async () => {
      const recent = await settled(discoveryApi.batch(viewedIds.slice(0, 12)), [] as Product[])

      const seen = new Set(viewedIds)
      const [alsoLists, buyAgainEntries, deals] = await Promise.all([
        Promise.all(viewedIds.slice(0, 3).map((id) => settled(discoveryApi.alsoViewed(id, 8), [] as Product[]))),
        authenticated ? settled(engageApi.buyAgain(12), []) : Promise.resolve([]),
        recent.length > 0 ? settled(discoveryApi.activeDeals(), [] as Deal[]) : Promise.resolve([] as Deal[]),
      ])

      const recommended: Product[] = []
      const added = new Set<string>()
      for (const p of alsoLists.flat()) {
        if (seen.has(p.id) || added.has(p.id)) continue
        added.add(p.id)
        recommended.push(p)
      }

      const buyAgain = await settled(discoveryApi.batch([...new Set(buyAgainEntries.map((e) => e.productId))]), [] as Product[])

      const categories = new Set(recent.map((p) => p.categoryId).filter((c): c is string => !!c))
      const dealProducts =
        categories.size > 0 && deals.length > 0
          ? await settled(discoveryApi.batch([...new Set(deals.map((d) => d.productId))].slice(0, 24)), [] as Product[])
          : []
      const dealsForYou = dealProducts.filter((p) => p.categoryId && categories.has(p.categoryId)).slice(0, 12)

      if (live) setSections({ recent, recommended: recommended.slice(0, 12), buyAgain, dealsForYou })
    })()

    return () => {
      live = false
    }
  }, [authenticated])

  return (
    <>
      <Shelf title="Continue where you left off" headingId="home-recent" products={sections.recent} />
      <Shelf title="Recommended for you" headingId="home-recommended" products={sections.recommended} />
      <Shelf title="Buy again" headingId="home-buy-again" products={sections.buyAgain} />
      <Shelf title="Deals for you" headingId="home-deals-for-you" products={sections.dealsForYou} />
    </>
  )
}
