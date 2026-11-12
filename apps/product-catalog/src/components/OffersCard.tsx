/**
 * PDP offers: coupons, bank offers and EMI plans for the current price (display only).
 */

import { useEffect, useState } from 'react'
import { formatMoney } from '../lib/format'
import { discoveryApi } from '../services/discoveryApi'
import type { OffersResponse } from '../types'

interface Props {
  productId: string
  price: number
  currency: string
}

export function OffersCard({ productId, price, currency }: Props) {
  const [offers, setOffers] = useState<OffersResponse | undefined>()

  useEffect(() => {
    let live = true
    discoveryApi
      .offers(productId, price, currency)
      .then((o) => {
        if (live) setOffers(o)
      })
      .catch(() => {
        if (live) setOffers(undefined)
      })
    return () => {
      live = false
    }
  }, [productId, price, currency])

  if (!offers || (offers.coupons.length === 0 && offers.bankOffers.length === 0 && offers.emi.length === 0)) return null

  return (
    <section aria-labelledby="offers-heading" className="space-y-3 rounded-2xl border-2 border-dashed border-outline p-4">
      <h2 id="offers-heading" className="text-sm font-bold uppercase tracking-[0.18em] text-accent-2">
        Offers
      </h2>
      {offers.coupons.length > 0 && (
        <ul className="space-y-1.5 text-sm">
          {offers.coupons.map((c) => (
            <li key={c.code} className="flex flex-wrap items-center gap-2">
              <span className="rounded border-2 border-dashed border-ink bg-vivid-amber/30 px-2 py-0.5 font-mono text-xs font-bold">{c.code}</span>
              <span className="text-on-surface">
                {c.description}
                {c.discountAmount > 0 && <span className="font-semibold text-success"> (save {formatMoney(c.discountAmount, currency)})</span>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {offers.bankOffers.length > 0 && (
        <ul className="space-y-1.5 text-sm">
          {offers.bankOffers.map((b) => (
            <li key={b.id} className="text-on-surface">
              <span className="font-bold">{b.bank}</span> — {b.title}
              {b.discountAmount > 0 && <span className="font-semibold text-success"> (save {formatMoney(b.discountAmount, currency)})</span>}
            </li>
          ))}
        </ul>
      )}
      {offers.emi.length > 0 && (
        <details className="text-sm">
          <summary className="cursor-pointer font-semibold text-primary">
            EMI from {formatMoney(Math.min(...offers.emi.map((e) => e.monthlyAmount)), currency)}/month
          </summary>
          <table className="mt-2 w-full text-left">
            <caption className="sr-only">EMI plans</caption>
            <thead>
              <tr className="text-xs text-on-surface-muted">
                <th scope="col" className="py-1 font-semibold">Tenure</th>
                <th scope="col" className="py-1 font-semibold">Monthly</th>
                <th scope="col" className="py-1 font-semibold">Interest</th>
              </tr>
            </thead>
            <tbody>
              {offers.emi.map((e) => (
                <tr key={e.months} className="border-t border-outline">
                  <td className="py-1">{e.months} months</td>
                  <td className="py-1 font-semibold">{formatMoney(e.monthlyAmount, currency)}</td>
                  <td className="py-1">{e.noCost ? <span className="font-semibold text-success">No cost</span> : `${e.interestRate}% p.a.`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
    </section>
  )
}
