/**
 * Checkout: pick or add a shipping address, apply a coupon, choose a payment method, place the order.
 * Works on the cart, or on a single product when opened from Buy Now (?buyNow=<id>&qty=<n>), in which
 * case the cart is never touched. The Idempotency-Key is generated per request shape and reused on
 * retries so a double click or a network retry can never create two orders.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { AddressForm, formatAddressLines } from '../components/AddressForm'
import { ErrorAlert } from '../components/ErrorAlert'
import { Field, LoadingBlock, cardClass, inputClass, primaryButtonClass, secondaryButtonClass } from '../components/Form'
import { PaymentPanel } from '../components/PaymentPanel'
import { ProductImage } from '../components/ProductImage'
import { useLoad } from '../hooks/useLoad'
import { useProduct } from '../hooks/useProduct'
import { errorMessage, isApiError, problemCode } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { effectivePrice } from '../lib/price'
import { orderApi } from '../services/orderApi'
import { promotionApi } from '../services/paymentApi'
import { userApi } from '../services/userApi'
import { useCartStore } from '../stores'
import type {
  Address,
  AddressRequest,
  AvailableCoupon,
  CouponValidation,
  DeliveryEstimate,
  Order,
  PaymentMethod,
} from '../types'

interface Line {
  productId: string
  quantity: number
  name: string
  price: number
  currency: string
  image?: string
}

interface StockShortfall {
  productId: string
  requested: number
  available: number
}

const COD_LIMIT_INR = 50000
const COD_LIMIT_OTHER = 1000
const GST_RATE = 18

function stockShortfalls(error: unknown): StockShortfall[] {
  if (!isApiError(error) || problemCode(error) !== 'OUT_OF_STOCK') return []
  const items = error.problem['items']
  return Array.isArray(items) ? (items as StockShortfall[]) : []
}

export function CheckoutPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const buyNowId = params.get('buyNow') ?? undefined
  const buyNowQty = Math.min(Math.max(Number.parseInt(params.get('qty') ?? '1', 10) || 1, 1), 99)
  const buyNow = useProduct(buyNowId)
  const cartItems = useCartStore((s) => s.items)

  const lines: Line[] = useMemo(() => {
    if (buyNowId) {
      if (buyNow.status !== 'success') return []
      const p = buyNow.data
      return [
        {
          productId: p.id,
          quantity: buyNowQty,
          name: p.name,
          price: effectivePrice(p),
          currency: p.priceCurrency,
          image: p.images[0],
        },
      ]
    }
    return cartItems.flatMap((i) =>
      i.snapshot
        ? [
            {
              productId: i.productId,
              quantity: i.quantity,
              name: i.snapshot.name,
              price: i.snapshot.priceAmount,
              currency: i.snapshot.priceCurrency,
              image: i.snapshot.image,
            },
          ]
        : [],
    )
  }, [buyNowId, buyNow, buyNowQty, cartItems])

  const loadAddresses = useCallback(() => userApi.listAddresses(), [])
  const { data: addresses, error, loading, reload } = useLoad(loadAddresses)
  const [selectedId, setSelectedId] = useState<string | undefined>()
  const [adding, setAdding] = useState(false)
  const [placing, setPlacing] = useState(false)
  const [placeError, setPlaceError] = useState<string | undefined>()
  const [shortfalls, setShortfalls] = useState<StockShortfall[]>([])
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>('CARD')
  const [couponInput, setCouponInput] = useState('')
  const [coupon, setCoupon] = useState<CouponValidation | null>(null)
  const [couponError, setCouponError] = useState<string | undefined>()
  const [couponBusy, setCouponBusy] = useState(false)
  const [available, setAvailable] = useState<AvailableCoupon[]>([])
  const [estimate, setEstimate] = useState<DeliveryEstimate | null>(null)
  const [estimateError, setEstimateError] = useState<string | undefined>()
  const [placed, setPlaced] = useState<Order | null>(null)
  const attempt = useRef<{ shape: string; key: string } | null>(null)

  const currency = lines[0]?.currency ?? 'INR'
  const mixedCurrency = lines.some((l) => l.currency !== currency)
  const subtotal = lines.reduce((sum, l) => sum + l.price * l.quantity, 0)
  const selected: Address | undefined = addresses?.find((a) => a.id === selectedId)

  // Default to the default address (the server lists it first) once addresses arrive.
  useEffect(() => {
    if (addresses && !addresses.some((a) => a.id === selectedId)) setSelectedId(addresses[0]?.id)
  }, [addresses, selectedId])

  // Delivery date, shipping fee and COD availability depend on the address pincode and subtotal.
  const pincode = selected?.postalCode
  useEffect(() => {
    if (!pincode || subtotal <= 0) {
      setEstimate(null)
      return
    }
    let active = true
    setEstimateError(undefined)
    orderApi
      .deliveryEstimate(pincode, subtotal, currency)
      .then((e) => {
        if (active) setEstimate(e)
      })
      .catch((err: unknown) => {
        if (!active) return
        setEstimate(null)
        setEstimateError(errorMessage(err))
      })
    return () => {
      active = false
    }
  }, [pincode, subtotal, currency])

  // Coupons the user can still use; failure just hides the list.
  useEffect(() => {
    if (subtotal <= 0) return
    let active = true
    promotionApi
      .available(subtotal, currency)
      .then((list) => {
        if (active) setAvailable(list)
      })
      .catch(() => {
        if (active) setAvailable([])
      })
    return () => {
      active = false
    }
  }, [subtotal, currency])

  const discount = coupon?.discountAmount ?? 0
  const shipping = estimate?.shippingFee ?? 0
  const total = Math.max(subtotal - discount, 0) + shipping
  const codLimit = currency === 'INR' ? COD_LIMIT_INR : COD_LIMIT_OTHER
  const codReason = !estimate
    ? 'Choose a deliverable address to check Cash on Delivery.'
    : !estimate.codAvailable
      ? 'Cash on Delivery is not available for this address.'
      : total > codLimit
        ? `Cash on Delivery is available up to ${formatMoney(codLimit, currency)}.`
        : undefined

  // Fall back to card if COD stops being possible after the address or cart changed.
  useEffect(() => {
    if (paymentMethod === 'COD' && codReason) setPaymentMethod('CARD')
  }, [paymentMethod, codReason])

  const applyCoupon = async (code: string) => {
    const trimmed = code.trim()
    if (!trimmed) return
    setCouponBusy(true)
    setCouponError(undefined)
    try {
      const result = await promotionApi.validate(trimmed, subtotal, currency)
      setCoupon(result)
      setCouponInput('')
    } catch (err) {
      setCoupon(null)
      setCouponError(errorMessage(err))
    } finally {
      setCouponBusy(false)
    }
  }

  const goToOrder = (orderId: string, paymentReturn: boolean) => {
    navigate(paymentReturn ? `/orders/${orderId}?payment=return` : `/orders/${orderId}`, {
      replace: true,
      state: { placed: true },
    })
  }

  if (buyNowId && buyNow.status === 'loading') return <LoadingBlock label="Loading product" />
  if (lines.length === 0 && !placed) {
    return (
      <div className={`${cardClass} mx-auto max-w-lg space-y-3 text-center`}>
        <p className="font-semibold text-on-surface">{buyNowId ? 'That product is not available' : 'Your cart is empty'}</p>
        <Link to="/" className="font-semibold text-primary hover:underline">
          Continue shopping
        </Link>
      </div>
    )
  }

  // Pay for an order that was already created (card): nothing else on the page is editable.
  if (placed?.payment) {
    return (
      <div className="mx-auto max-w-xl space-y-5">
        <h1 className="font-display text-2xl font-bold text-on-surface">Complete your payment</h1>
        <p className="text-on-surface-muted">
          Order #{placed.id.slice(0, 8)} &middot; {formatMoney(Number(placed.totalAmount), placed.currency)}
        </p>
        <PaymentPanel orderId={placed.id} payment={placed.payment} onSubmitted={() => goToOrder(placed.id, true)} />
        <Link to={`/orders/${placed.id}`} className="inline-block text-sm font-semibold text-primary hover:underline">
          Pay later from the order page
        </Link>
      </div>
    )
  }

  if (loading && !addresses) return <LoadingBlock label="Loading addresses" />
  if (error) return <ErrorAlert message={errorMessage(error)} />

  const addAddress = async (value: AddressRequest) => {
    const created = await userApi.createAddress(value)
    setAdding(false)
    setSelectedId(created.id)
    reload()
  }

  const place = async () => {
    if (!selectedId) return
    const request = {
      addressId: selectedId,
      paymentMethod,
      ...(coupon ? { couponCode: coupon.code } : {}),
      ...(buyNowId ? { items: lines.map((l) => ({ productId: l.productId, quantity: l.quantity })) } : {}),
    }
    const shape = JSON.stringify(request)
    if (attempt.current?.shape !== shape) attempt.current = { shape, key: crypto.randomUUID() }
    setPlacing(true)
    setPlaceError(undefined)
    setShortfalls([])
    try {
      const order = await orderApi.place(request, attempt.current.key)
      attempt.current = null
      // Re-adopt the (now cleared) server cart so the drawer and badge update.
      if (!buyNowId) void useCartStore.getState().attachServer()
      if (paymentMethod === 'CARD' && order.payment && order.status === 'PENDING_PAYMENT') {
        setPlaced(order)
        setPlacing(false)
        return
      }
      goToOrder(order.id, false)
    } catch (err) {
      setShortfalls(stockShortfalls(err))
      setPlaceError(errorMessage(err))
      // A rejected request may succeed with a new key once the cause is fixed.
      if (isApiError(err) && err.status < 500) attempt.current = null
      setPlacing(false)
    }
  }

  const showForm = adding || addresses?.length === 0
  const nameOf = (productId: string) => lines.find((l) => l.productId === productId)?.name ?? 'Item'
  const estimatedTax = currency === 'INR' ? (total * GST_RATE) / (100 + GST_RATE) : 0

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_24rem]">
      <div className="space-y-5">
        <h1 className="font-display text-2xl font-bold text-on-surface">Checkout</h1>
        {placeError && <ErrorAlert message={placeError} onDismiss={() => setPlaceError(undefined)} />}
        {shortfalls.length > 0 && (
          <ul className="space-y-1 rounded-lg border-2 border-ink bg-surface-variant p-4 text-sm text-on-surface" role="alert">
            {shortfalls.map((s) => (
              <li key={s.productId}>
                <span className="font-semibold">{nameOf(s.productId)}</span>: you asked for {s.requested}, only {s.available}{' '}
                available.
              </li>
            ))}
          </ul>
        )}
        {mixedCurrency && (
          <ErrorAlert message="Items in your cart use different currencies and cannot be ordered together." />
        )}

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
          <div aria-live="polite" className="text-sm">
            {estimate && (
              <p className="font-semibold text-success">
                Delivery by{' '}
                {new Date(`${estimate.estimatedDeliveryDate}T00:00:00`).toLocaleDateString(undefined, {
                  weekday: 'short',
                  day: 'numeric',
                  month: 'short',
                })}{' '}
                &middot; {estimate.shippingFee > 0 ? formatMoney(estimate.shippingFee, estimate.currency) : 'Free shipping'}
              </p>
            )}
            {estimateError && <p className="text-error">{estimateError}</p>}
          </div>
        </section>

        <fieldset className="space-y-3">
          <legend className="mb-2 font-bold text-on-surface">Payment method</legend>
          <div role="radiogroup" aria-label="Payment method" className="grid gap-3 sm:grid-cols-2">
            <label className={`${cardClass} block cursor-pointer ${paymentMethod === 'CARD' ? 'ring-2 ring-primary' : ''}`}>
              <input
                type="radio"
                name="paymentMethod"
                className="mr-2"
                checked={paymentMethod === 'CARD'}
                onChange={() => setPaymentMethod('CARD')}
              />
              <span className="font-bold text-on-surface">Card</span>
              <span className="mt-1 block text-sm text-on-surface-muted">Pay securely online</span>
            </label>
            <label
              className={`${cardClass} block ${codReason ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'} ${
                paymentMethod === 'COD' ? 'ring-2 ring-primary' : ''
              }`}
            >
              <input
                type="radio"
                name="paymentMethod"
                className="mr-2"
                checked={paymentMethod === 'COD'}
                disabled={!!codReason}
                onChange={() => setPaymentMethod('COD')}
                aria-describedby="cod-reason"
              />
              <span className="font-bold text-on-surface">Cash on Delivery</span>
              <span id="cod-reason" className="mt-1 block text-sm text-on-surface-muted">
                {codReason ?? 'Pay when your order arrives'}
              </span>
            </label>
          </div>
        </fieldset>
      </div>

      <aside className={`${cardClass} h-fit space-y-4`}>
        <h2 className="font-bold text-on-surface">Order summary</h2>
        <ul className="divide-y divide-outline">
          {lines.map((line) => (
            <li key={line.productId} className="flex gap-3 py-2">
              <div className="h-12 w-12 shrink-0 overflow-hidden rounded-lg bg-surface-variant">
                <ProductImage src={line.image} alt={line.name} productId={line.productId} />
              </div>
              <div className="min-w-0 flex-1 text-sm">
                <p className="line-clamp-2 text-on-surface">{line.name}</p>
                <p className="text-xs text-on-surface-muted">Qty {line.quantity}</p>
              </div>
              <span className="text-sm font-bold text-on-surface">{formatMoney(line.price * line.quantity, line.currency)}</span>
            </li>
          ))}
        </ul>

        <div className="space-y-2">
          {coupon ? (
            <div className="flex items-center justify-between rounded-lg border-2 border-ink bg-primary-container px-3 py-2 text-sm text-on-primary-container">
              <span>
                <span className="font-bold">{coupon.code}</span> applied
              </span>
              <button type="button" className="font-semibold underline" onClick={() => setCoupon(null)}>
                Remove
              </button>
            </div>
          ) : (
            <>
              <Field label="Coupon code">
                {(id) => (
                  <div className="flex gap-2">
                    <input
                      id={id}
                      className={inputClass}
                      value={couponInput}
                      maxLength={40}
                      autoCapitalize="characters"
                      onChange={(e) => setCouponInput(e.target.value.toUpperCase())}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault()
                          void applyCoupon(couponInput)
                        }
                      }}
                    />
                    <button
                      type="button"
                      className={secondaryButtonClass}
                      disabled={couponBusy || !couponInput.trim()}
                      onClick={() => void applyCoupon(couponInput)}
                    >
                      Apply
                    </button>
                  </div>
                )}
              </Field>
              <p aria-live="polite" className="text-xs text-error">
                {couponError}
              </p>
              {available.length > 0 && (
                <ul className="flex flex-wrap gap-2" aria-label="Available coupons">
                  {available.map((c) => (
                    <li key={c.code}>
                      <button
                        type="button"
                        onClick={() => void applyCoupon(c.code)}
                        title={c.description ?? undefined}
                        className="press rounded-full border-2 border-dashed border-ink bg-surface px-3 py-1 text-xs font-bold text-on-surface hover:bg-surface-variant"
                      >
                        {c.code}
                        {c.discountAmount ? ` (save ${formatMoney(c.discountAmount, currency)})` : ''}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </div>

        <dl className="space-y-1 text-sm">
          <div className="flex justify-between">
            <dt className="text-on-surface-muted">Subtotal</dt>
            <dd className="text-on-surface">{formatMoney(subtotal, currency)}</dd>
          </div>
          {discount > 0 && (
            <div className="flex justify-between">
              <dt className="text-on-surface-muted">Discount</dt>
              <dd className="text-success">-{formatMoney(discount, currency)}</dd>
            </div>
          )}
          <div className="flex justify-between">
            <dt className="text-on-surface-muted">Shipping</dt>
            <dd className="text-on-surface">{!estimate ? '—' : shipping > 0 ? formatMoney(shipping, currency) : 'Free'}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-on-surface-muted">{currency === 'INR' ? `Includes GST (${GST_RATE}%)` : 'Tax included'}</dt>
            <dd className="text-on-surface">{currency === 'INR' ? `~${formatMoney(estimatedTax, currency)}` : '—'}</dd>
          </div>
          <div className="flex items-baseline justify-between border-t-2 border-outline pt-2">
            <dt className="font-bold text-on-surface">Total</dt>
            <dd className="text-xl font-bold text-on-surface">{formatMoney(total, currency)}</dd>
          </div>
        </dl>
        <p className="text-xs text-on-surface-muted">Final prices, tax and discounts are confirmed by the store when the order is placed.</p>
        <button
          type="button"
          onClick={() => void place()}
          disabled={placing || !selectedId || showForm || mixedCurrency}
          className={`${primaryButtonClass} w-full`}
        >
          {placing ? 'Placing order...' : paymentMethod === 'CARD' ? 'Place order and pay' : 'Place order'}
        </button>
      </aside>
    </div>
  )
}
