/**
 * Checkout: pick or add a shipping address, review the cart, place the order.
 * The Idempotency-Key is generated per (address) attempt and reused on retries so a double
 * click or a network retry can never create two orders.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AddressForm, formatAddressLines } from '../components/AddressForm'
import { ErrorAlert } from '../components/ErrorAlert'
import { LoadingBlock, cardClass, primaryButtonClass, secondaryButtonClass } from '../components/Form'
import { ProductImage } from '../components/ProductImage'
import { useLoad } from '../hooks/useLoad'
import { errorMessage } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { orderApi } from '../services/orderApi'
import { userApi } from '../services/userApi'
import { useCartStore } from '../stores'
import type { AddressRequest } from '../types'

export function CheckoutPage() {
  const navigate = useNavigate()
  const items = useCartStore((s) => s.items)
  const load = useCallback(() => userApi.listAddresses(), [])
  const { data: addresses, error, loading, reload } = useLoad(load)
  const [selectedId, setSelectedId] = useState<string | undefined>()
  const [adding, setAdding] = useState(false)
  const [placing, setPlacing] = useState(false)
  const [placeError, setPlaceError] = useState<string | undefined>()
  const attempt = useRef<{ addressId: string; key: string } | null>(null)

  // Default to the default address (the server lists it first) once addresses arrive.
  useEffect(() => {
    if (addresses && !addresses.some((a) => a.id === selectedId)) setSelectedId(addresses[0]?.id)
  }, [addresses, selectedId])

  if (items.length === 0) {
    return (
      <div className={`${cardClass} mx-auto max-w-lg space-y-3 text-center`}>
        <p className="font-semibold text-on-surface">Your cart is empty</p>
        <Link to="/" className="font-semibold text-primary hover:underline">
          Continue shopping
        </Link>
      </div>
    )
  }
  if (loading && !addresses) return <LoadingBlock label="Loading addresses" />
  if (error) return <ErrorAlert message={errorMessage(error)} />

  // Never add amounts across currencies; the server rejects mixed carts anyway.
  const totals = new Map<string, number>()
  for (const item of items) {
    if (!item.snapshot) continue
    const { priceCurrency, priceAmount } = item.snapshot
    totals.set(priceCurrency, (totals.get(priceCurrency) ?? 0) + priceAmount * item.quantity)
  }

  const addAddress = async (value: AddressRequest) => {
    const created = await userApi.createAddress(value)
    setAdding(false)
    setSelectedId(created.id)
    reload()
  }

  const place = async () => {
    if (!selectedId) return
    if (attempt.current?.addressId !== selectedId) {
      attempt.current = { addressId: selectedId, key: crypto.randomUUID() }
    }
    setPlacing(true)
    setPlaceError(undefined)
    try {
      const order = await orderApi.place(selectedId, attempt.current.key)
      attempt.current = null
      // Re-adopt the (now cleared) server cart so the drawer and badge update.
      void useCartStore.getState().attachServer()
      navigate(`/orders/${order.id}`, { replace: true, state: { placed: true } })
    } catch (err) {
      setPlaceError(errorMessage(err))
      setPlacing(false)
    }
  }

  const showForm = adding || addresses?.length === 0

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_22rem]">
      <div className="space-y-5">
        <h1 className="font-display text-2xl font-bold text-on-surface">Checkout</h1>
        {placeError && <ErrorAlert message={placeError} onDismiss={() => setPlaceError(undefined)} />}

        <section className="space-y-3">
          <h2 className="font-bold text-on-surface">Shipping address</h2>
          {showForm ? (
            <div className={cardClass}>
              <AddressForm
                submitLabel="Save and use this address"
                onSubmit={addAddress}
                onCancel={addresses?.length ? () => setAdding(false) : undefined}
              />
            </div>
          ) : (
            <>
              <ul className="grid gap-3 sm:grid-cols-2">
                {addresses?.map((a) => (
                  <li key={a.id}>
                    <label
                      className={`${cardClass} block cursor-pointer ${selectedId === a.id ? 'ring-2 ring-primary' : ''}`}
                    >
                      <input
                        type="radio"
                        name="address"
                        className="mr-2"
                        checked={selectedId === a.id}
                        onChange={() => setSelectedId(a.id)}
                      />
                      <span className="font-bold text-on-surface">{a.label || 'Address'}</span>
                      {a.isDefault && <span className="ml-2 text-xs text-on-surface-muted">(default)</span>}
                      <address className="mt-2 text-sm not-italic text-on-surface">
                        {formatAddressLines(a).map((line, i) => (
                          <div key={i}>{line}</div>
                        ))}
                      </address>
                    </label>
                  </li>
                ))}
              </ul>
              <button type="button" className={secondaryButtonClass} onClick={() => setAdding(true)}>
                Add a new address
              </button>
            </>
          )}
        </section>
      </div>

      <aside className={`${cardClass} h-fit space-y-4`}>
        <h2 className="font-bold text-on-surface">Order summary</h2>
        <ul className="divide-y divide-outline">
          {items.map((item) => (
            <li key={item.productId} className="flex gap-3 py-2">
              <div className="h-12 w-12 shrink-0 overflow-hidden rounded-lg bg-surface-variant">
                <ProductImage src={item.snapshot?.image} alt={item.snapshot?.name ?? 'Product'} productId={item.productId} />
              </div>
              <div className="min-w-0 flex-1 text-sm">
                <p className="line-clamp-2 text-on-surface">{item.snapshot?.name ?? 'Product'}</p>
                <p className="text-xs text-on-surface-muted">Qty {item.quantity}</p>
              </div>
              {item.snapshot && (
                <span className="text-sm font-bold text-on-surface">
                  {formatMoney(item.snapshot.priceAmount * item.quantity, item.snapshot.priceCurrency)}
                </span>
              )}
            </li>
          ))}
        </ul>
        {[...totals].map(([currency, amount]) => (
          <div key={currency} className="flex items-baseline justify-between">
            <span className="text-on-surface-muted">Total</span>
            <span className="text-xl font-bold text-on-surface">{formatMoney(amount, currency)}</span>
          </div>
        ))}
        <p className="text-xs text-on-surface-muted">Final prices are confirmed by the store when the order is placed.</p>
        <button
          type="button"
          onClick={() => void place()}
          disabled={placing || !selectedId || showForm}
          className={`${primaryButtonClass} w-full`}
        >
          {placing ? 'Placing order...' : 'Place order'}
        </button>
      </aside>
    </div>
  )
}
