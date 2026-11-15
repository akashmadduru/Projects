/**
 * PDP offers (display only): two summary tiles - Coupons and Bank offers - each opening its own
 * dialog, plus an EMI disclosure. The tiles show the count and the best saving so the shopper can
 * judge value without opening anything; the dialogs hold the detail and a one-tap "Copy code".
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { formatMoney } from '../lib/format'
import { discoveryApi } from '../services/discoveryApi'
import { useUiStore } from '../stores'
import type { BankOffer, CouponOffer, OffersResponse } from '../types'
import { CopyIcon, TagIcon } from './Icons'
import { Modal } from './Modal'

interface Props {
  productId: string
  price: number
  currency: string
}

type Open = 'coupons' | 'bank' | null

/** First letters of the first two words: "HDFC Bank" -> "HB". Stable avatar without logo assets. */
function initials(name: string): string {
  return (
    name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]!.toUpperCase())
      .join('') || '?'
  )
}

function maxSaving(items: { discountAmount: number }[]): number {
  return items.reduce((m, o) => Math.max(m, o.discountAmount), 0)
}

function OfferTile({
  icon,
  title,
  count,
  best,
  currency,
  onOpen,
  testId,
}: {
  icon: React.ReactNode
  title: string
  count: number
  best: number
  currency: string
  onOpen: () => void
  testId: string
}) {
  return (
    <button
      type="button"
      onClick={onOpen}
      aria-haspopup="dialog"
      data-testid={testId}
      className="press group flex w-full items-center gap-3 rounded-2xl border-2 border-ink bg-surface p-3 text-left shadow-card transition-transform hover:-translate-y-0.5"
    >
      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl border-2 border-ink bg-vivid-amber/40 text-on-surface">{icon}</span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-bold">
          {title} <span className="ml-1 rounded-full bg-primary px-2 py-0.5 text-xs text-on-primary">{count}</span>
        </span>
        <span className="block truncate text-xs text-on-surface-muted">
          {best > 0 ? (
            <>
              Save up to <span className="font-semibold text-success">{formatMoney(best, currency)}</span>
            </>
          ) : (
            'View details'
          )}
        </span>
      </span>
      <span aria-hidden="true" className="text-lg transition-transform group-hover:translate-x-1">
        ›
      </span>
    </button>
  )
}

function CouponRow({ coupon, best, currency }: { coupon: CouponOffer; best: boolean; currency: string }) {
  const notify = useUiStore((s) => s.notify)
  const [copied, setCopied] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  useEffect(() => () => clearTimeout(timer.current), [])

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(coupon.code)
      setCopied(true)
      notify(`Code ${coupon.code} copied. Apply it at checkout.`)
      clearTimeout(timer.current)
      timer.current = setTimeout(() => setCopied(false), 2000)
    } catch {
      notify(`Your browser blocked copying. The code is ${coupon.code}.`)
    }
  }

  return (
    <li className="flex overflow-hidden rounded-2xl border-2 border-ink bg-surface shadow-card">
      <div className="grid w-20 shrink-0 place-items-center border-r-2 border-dashed border-ink bg-vivid-amber/30 px-2 text-center">
        {coupon.discountAmount > 0 ? (
          <span className="text-xs font-bold leading-tight">
            SAVE
            <span className="block text-base">{formatMoney(coupon.discountAmount, currency)}</span>
          </span>
        ) : (
          <TagIcon className="h-6 w-6" />
        )}
      </div>
      <div className="min-w-0 flex-1 p-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded border-2 border-dashed border-ink bg-surface-variant px-2 py-0.5 font-mono text-sm font-bold tracking-wider">
            {coupon.code}
          </span>
          {best && <span className="rounded-full bg-success px-2 py-0.5 text-[11px] font-bold text-on-primary">Best value</span>}
        </div>
        <p className="mt-1 text-sm text-on-surface">{coupon.description}</p>
        <button
          type="button"
          onClick={copy}
          className="press mt-2 inline-flex items-center gap-1.5 rounded-full border-2 border-ink bg-accent px-3 py-1 text-xs font-bold text-on-accent shadow-cta"
          aria-label={`Copy code ${coupon.code}`}
        >
          <CopyIcon className="h-3.5 w-3.5" />
          {copied ? 'Copied ✓' : 'Copy code'}
        </button>
      </div>
    </li>
  )
}

function BankGroup({ bank, offers, bestId, currency }: { bank: string; offers: BankOffer[]; bestId: string | null; currency: string }) {
  return (
    <li>
      <div className="mb-1.5 flex items-center gap-2">
        <span aria-hidden="true" className="grid h-8 w-8 place-items-center rounded-full border-2 border-ink bg-primary text-xs font-bold text-on-primary">
          {initials(bank)}
        </span>
        <h3 className="text-sm font-bold">{bank}</h3>
      </div>
      <ul className="space-y-2">
        {offers.map((b) => (
          <li key={b.id} className="flex items-start justify-between gap-3 rounded-2xl border-2 border-ink bg-surface p-3 shadow-card">
            <div className="min-w-0">
              <p className="text-sm text-on-surface">{b.title}</p>
              {b.id === bestId && <span className="mt-1 inline-block rounded-full bg-success px-2 py-0.5 text-[11px] font-bold text-on-primary">Best value</span>}
            </div>
            {b.discountAmount > 0 && (
              <span className="shrink-0 text-right text-sm font-bold text-success">
                Save
                <span className="block">{formatMoney(b.discountAmount, currency)}</span>
              </span>
            )}
          </li>
        ))}
      </ul>
    </li>
  )
}

export function OffersCard({ productId, price, currency }: Props) {
  const [offers, setOffers] = useState<OffersResponse | undefined>()
  const [open, setOpen] = useState<Open>(null)
  const close = useCallback(() => setOpen(null), [])

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

  const coupons = useMemo(() => [...(offers?.coupons ?? [])].sort((a, b) => b.discountAmount - a.discountAmount), [offers])
  const bankGroups = useMemo(() => {
    const byBank = new Map<string, BankOffer[]>()
    for (const b of [...(offers?.bankOffers ?? [])].sort((a, c) => c.discountAmount - a.discountAmount)) {
      byBank.set(b.bank, [...(byBank.get(b.bank) ?? []), b])
    }
    return [...byBank.entries()]
  }, [offers])

  if (!offers || (offers.coupons.length === 0 && offers.bankOffers.length === 0 && offers.emi.length === 0)) return null

  const bestCoupon = maxSaving(coupons)
  const bestBank = maxSaving(offers.bankOffers)
  // Highlight only when there is a real winner among several offers.
  const bestCouponCode = coupons.length > 1 && bestCoupon > 0 ? coupons[0]!.code : null
  const topBank = [...offers.bankOffers].sort((a, b) => b.discountAmount - a.discountAmount)[0]
  const bestBankId = offers.bankOffers.length > 1 && bestBank > 0 && topBank ? topBank.id : null

  return (
    <section aria-labelledby="offers-heading" className="space-y-3 rounded-2xl border-2 border-dashed border-outline p-4">
      <h2 id="offers-heading" className="text-sm font-bold uppercase tracking-[0.18em] text-accent-2">
        Offers
      </h2>

      <div className="grid gap-3 sm:grid-cols-2">
        {coupons.length > 0 && (
          <OfferTile icon={<TagIcon className="h-5 w-5" />} title="Coupons" count={coupons.length} best={bestCoupon} currency={currency} onOpen={() => setOpen('coupons')} testId="offers-coupons-tile" />
        )}
        {offers.bankOffers.length > 0 && (
          <OfferTile
            icon={<span aria-hidden="true">🏦</span>}
            title="Bank offers"
            count={offers.bankOffers.length}
            best={bestBank}
            currency={currency}
            onOpen={() => setOpen('bank')}
            testId="offers-bank-tile"
          />
        )}
      </div>

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

      <Modal
        open={open === 'coupons'}
        onClose={close}
        title="Coupons"
        subtitle={`${coupons.length} available for this price`}
        footer="Copy a code, then paste it at checkout. Only one coupon applies per order."
      >
        <ul className="space-y-3">
          {coupons.map((c) => (
            <CouponRow key={c.code} coupon={c} best={c.code === bestCouponCode} currency={currency} />
          ))}
        </ul>
      </Modal>

      <Modal
        open={open === 'bank'}
        onClose={close}
        title="Bank offers"
        subtitle={`${offers.bankOffers.length} offer${offers.bankOffers.length === 1 ? '' : 's'} from ${bankGroups.length} bank${bankGroups.length === 1 ? '' : 's'}`}
        footer="Bank offers apply automatically when you pay with an eligible card. Savings are shown for this price."
      >
        <ul className="space-y-4">
          {bankGroups.map(([bank, list]) => (
            <BankGroup key={bank} bank={bank} offers={list} bestId={bestBankId} currency={currency} />
          ))}
        </ul>
      </Modal>
    </section>
  )
}
