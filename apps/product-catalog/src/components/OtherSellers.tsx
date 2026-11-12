/**
 * "Other sellers" panel on the product page: every active offer with its price, rating and dispatch time,
 * and an add-to-cart per offer. The buy box offer is tagged.
 */

import { Link } from 'react-router-dom'
import { AddToCartButton } from './AddToCartButton'
import { formatMoney } from '../lib/format'
import type { Product, ProductOffer } from '../types'

interface Props {
  product: Product
  offers: ProductOffer[]
  buyBoxId?: string
  quantity: number
  /** Extra reason to disable every button (e.g. variant not chosen). */
  unavailable?: boolean
}

export const offerPrice = (offer: ProductOffer): number =>
  offer.salePrice && offer.salePrice > 0 ? offer.salePrice : offer.price

export function OtherSellers({ product, offers, buyBoxId, quantity, unavailable = false }: Props) {
  if (offers.length < 2) return null
  const sorted = [...offers].sort((a, b) => offerPrice(a) - offerPrice(b))
  const lowest = offerPrice(sorted[0]!)

  return (
    <section aria-labelledby="other-sellers-heading" className="space-y-3 rounded-2xl border-2 border-outline p-4">
      <h2 id="other-sellers-heading" className="text-base font-bold text-on-surface">
        Other sellers ({offers.length}) <span className="font-normal text-on-surface-muted">from {formatMoney(lowest, sorted[0]!.currency)}</span>
      </h2>
      <ul className="divide-y divide-outline/40">
        {sorted.map((offer) => {
          const soldOut = offer.availableQuantity === 0
          return (
            <li key={offer.offerId} className="flex flex-wrap items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
              <div className="min-w-0">
                <p className="flex flex-wrap items-center gap-2 text-sm font-semibold text-on-surface">
                  {offer.sellerSlug ? (
                    <Link to={`/seller/${offer.sellerSlug}`} className="hover:text-primary hover:underline">
                      {offer.sellerName}
                    </Link>
                  ) : (
                    offer.sellerName
                  )}
                  {offer.offerId === buyBoxId && (
                    <span className="rounded-full border-2 border-ink bg-vivid-amber px-2 py-0.5 text-[11px] font-bold text-ink">Best offer</span>
                  )}
                </p>
                <p className="text-xs text-on-surface-muted">
                  {offer.sellerRating != null ? `${offer.sellerRating.toFixed(1)} seller rating · ` : ''}
                  {soldOut ? 'Out of stock' : `Dispatches in ${offer.dispatchDays} ${offer.dispatchDays === 1 ? 'day' : 'days'}`}
                </p>
              </div>
              <div className="flex items-center gap-3">
                <span className="font-bold text-on-surface">{formatMoney(offerPrice(offer), offer.currency)}</span>
                <AddToCartButton
                  product={product}
                  price={offerPrice(offer)}
                  offerId={offer.offerId}
                  quantity={quantity}
                  unavailable={soldOut || unavailable}
                  label="Add"
                />
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
