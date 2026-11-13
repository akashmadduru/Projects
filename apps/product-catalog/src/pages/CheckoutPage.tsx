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
import { ExchangeOffer } from '../components/ExchangeOffer'
import { Field, LoadingBlock, cardClass, inputClass, primaryButtonClass, secondaryButtonClass } from '../components/Form'
import { PaymentPanel } from '../components/PaymentPanel'
import { ProductImage } from '../components/ProductImage'
import { useLoad } from '../hooks/useLoad'
import { useProduct } from '../hooks/useProduct'
import { errorMessage, isApiError, problemCode } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { effectivePrice } from '../lib/price'
import { orderApi } from '../services/orderApi'
import { engageApi } from '../services/engageApi'
import { promotionApi } from '../services/paymentApi'
import { useT } from '../lib/i18n'
import { userApi } from '../services/userApi'
import { useCartStore } from '../stores'
import type {
  Address,
  AddressRequest,
  AvailableCoupon,
  Coins,
  ExchangeQuote,
  CouponValidation,
  DeliveryEstimate,
  Order,
  PaymentMethod,
  Wallet,
} from '../types'

interface Line {
  productId: string
  offerId?: string
  variantId?: string
  variantLabel?: string
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
const COIN_CAP = 0.2

function stockShortfalls(error: unknown): StockShortfall[] {
  if (!isApiError(error) || problemCode(error) !== 'OUT_OF_STOCK') return []
  const items = error.problem['items']
  return Array.isArray(items) ? (items as StockShortfall[]) : []
}

export function CheckoutPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const buyNowId = params.get('buyNow') ?? undefined
  const buyNowVariantId = params.get('variant') ?? undefined
  const buyNowOfferId = params.get('offer') ?? undefined
  const buyNowQty = Math.min(Math.max(Number.parseInt(params.get('qty') ?? '1', 10) || 1, 1), 99)
  const buyNow = useProduct(buyNowId)
  const cartItems = useCartStore((s) => s.items)

  const lines: Line[] = useMemo(() => {
    if (buyNowId) {
      if (buyNow.status !== 'success') return []
      const p = buyNow.data
      const v = buyNowVariantId ? p.variants?.find((x) => x.id === buyNowVariantId) : undefined
      const vPrice = v ? (v.salePrice && v.salePrice > 0 ? v.salePrice : v.priceAmount) : null
      return [
        {
          productId: p.id,
          ...(buyNowOfferId ? { offerId: buyNowOfferId } : {}),
          ...(v ? { variantId: v.id, variantLabel: Object.values(v.attributes).join(' / ') } : {}),
          quantity: buyNowQty,
          name: p.name,
          price: vPrice ?? effectivePrice(p),
          currency: p.priceCurrency,
          image: v?.imageUrl ?? p.images[0],
        },
      ]
    }
    return cartItems.flatMap((i) =>
      i.snapshot
        ? [
            {
              productId: i.productId,
              ...(i.offerId ? { offerId: i.offerId } : {}),
              ...(i.variantId ? { variantId: i.variantId, variantLabel: i.variantLabel } : {}),
              quantity: i.quantity,
              name: i.snapshot.name,
              price: i.snapshot.priceAmount,
              currency: i.snapshot.priceCurrency,
              image: i.snapshot.image,
            },
          ]
        : [],
    )
  }, [buyNowId, buyNowVariantId, buyNowOfferId, buyNow, buyNowQty, cartItems])

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
  const [exchange, setExchange] = useState<ExchangeQuote | null>(null)
  // The server's risk engine can switch Cash on Delivery off for this order (PAYMENT_METHOD_UNAVAILABLE / RISK).
  const [codRisk, setCodRisk] = useState(false)
  const t = useT()
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

  // Wallet, coins and membership are optional extras: a failing service just hides its controls.
  const [wallet, setWallet] = useState<Wallet | null>(null)
  const [coins, setCoins] = useState<Coins | null>(null)
  const [member, setMember] = useState(false)
  const [useWallet, setUseWallet] = useState(false)
  const [coinsInput, setCoinsInput] = useState(0)
  useEffect(() => {
    let active = true
    void engageApi.wallet().then((w) => active && setWallet(w)).catch(() => undefined)
    void engageApi.coins(currency).then((c) => active && setCoins(c)).catch(() => undefined)
    void engageApi.membership().then((m) => active && setMember(m.active)).catch(() => undefined)
    return () => {
      active = false
    }
  }, [currency])

  const discount = coupon?.discountAmount ?? 0
  const coinUnit = currency === 'INR' ? 1 : 0.01
  const coinsUsable = coins && (coins.currency == null || coins.currency === currency)
  const maxCoins = coinsUsable
    ? Math.max(0, Math.min(coins.balance, Math.floor((Math.max(subtotal - discount, 0) * COIN_CAP) / coinUnit)))
    : 0
  const coinsToRedeem = Math.min(coinsInput, maxCoins)
  const coinsValue = coinsToRedeem * coinUnit
  const baseShipping = estimate?.shippingFee ?? 0
  const shipping = member ? 0 : baseShipping
  const exchangeValue = exchange ? Math.min(exchange.quotedValue, Math.max(subtotal - discount - coinsValue, 0)) : 0
  const total = Math.max(subtotal - discount - coinsValue - exchangeValue, 0) + shipping
  const walletUsable = !!wallet && wallet.balance > 0 && (wallet.currency == null || wallet.currency === currency)
  const walletCoversAll = walletUsable && wallet.balance >= total && total > 0
  const walletApplied = walletUsable && (useWallet || paymentMethod === 'WALLET') ? Math.min(wallet.balance, total) : 0
  const payable = Math.max(total - walletApplied, 0)
  const codLimit = currency === 'INR' ? COD_LIMIT_INR : COD_LIMIT_OTHER
  const codReason = codRisk
    ? 'Cash on Delivery is not available for this order. Please pay online.'
    : !estimate
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

  // The wallet method needs a balance covering the whole total; a toggle that covers it becomes the WALLET method.
  useEffect(() => {
    if (paymentMethod === 'WALLET' && !walletCoversAll) setPaymentMethod('CARD')
    else if (useWallet && walletCoversAll && paymentMethod !== 'WALLET') setPaymentMethod('WALLET')
  }, [paymentMethod, walletCoversAll, useWallet])

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
      ...(walletApplied > 0 ? { useWallet: true } : {}),
      ...(coinsToRedeem > 0 ? { coinsToRedeem } : {}),
      ...(exchange ? { exchangeQuoteId: exchange.id } : {}),
      ...(buyNowId ? { items: lines.map((l) => ({ productId: l.productId, ...(l.offerId ? { offerId: l.offerId } : {}), ...(l.variantId ? { variantId: l.variantId } : {}), quantity: l.quantity })) } : {}),
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
      if (isApiError(err) && problemCode(err) === 'PAYMENT_METHOD_UNAVAILABLE' && err.problem['reason'] === 'RISK') {
        setCodRisk(true)
        setPaymentMethod('CARD')
        setPlaceError('Cash on Delivery is not available for this order. Please choose online payment.')
        attempt.current = null
        setPlacing(false)
        return
      }
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
        <h1 className="font-display text-2xl font-bold text-on-surface">{t('Checkout')}</h1>
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
          <h2 className="font-bold text-on-surface">{t('Shipping address')}</h2>
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
          <legend className="mb-2 font-bold text-on-surface">{t('Payment method')}</legend>
          <div role="radiogroup" aria-label="Payment method" className="grid gap-3 sm:grid-cols-2">
            <label className={`${cardClass} block cursor-pointer ${paymentMethod === 'CARD' ? 'ring-2 ring-primary' : ''}`}>
              <input
                type="radio"
                name="paymentMethod"
                className="mr-2"
                checked={paymentMethod === 'CARD'}
                onChange={() => {
                  setPaymentMethod('CARD')
                  if (paymentMethod === 'WALLET') setUseWallet(false)
                }}
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
                onChange={() => {
                  if (paymentMethod === 'WALLET') setUseWallet(false)
                  setPaymentMethod('COD')
                }}
                aria-describedby="cod-reason"
              />
              <span className="font-bold text-on-surface">{t('Cash on Delivery')}</span>
              <span id="cod-reason" className="mt-1 block text-sm text-on-surface-muted">
                {codReason ?? 'Pay when your order arrives'}
              </span>
            </label>
            {walletUsable && (
              <label
                className={`${cardClass} block ${walletCoversAll ? 'cursor-pointer' : 'cursor-not-allowed opacity-60'} ${
                  paymentMethod === 'WALLET' ? 'ring-2 ring-primary' : ''
                }`}
              >
                <input
                  type="radio"
                  name="paymentMethod"
                  className="mr-2"
                  checked={paymentMethod === 'WALLET'}
                  disabled={!walletCoversAll}
                  onChange={() => {
                    setPaymentMethod('WALLET')
                    setUseWallet(true)
                  }}
                  aria-describedby="wallet-reason"
                />
                <span className="font-bold text-on-surface">Wallet</span>
                <span id="wallet-reason" className="mt-1 block text-sm text-on-surface-muted">
                  {walletCoversAll
                    ? `Pay in full from your ${formatMoney(wallet.balance, currency)} balance`
                    : `Balance ${formatMoney(wallet.balance, currency)} does not cover this order`}
                </span>
              </label>
            )}
          </div>
          {walletUsable && !walletCoversAll && (
            <label className="flex cursor-pointer items-center gap-2 text-sm text-on-surface">
              <input type="checkbox" checked={useWallet} onChange={(e) => setUseWallet(e.target.checked)} />
              Use wallet balance for part of this order ({formatMoney(Math.min(wallet.balance, total), currency)} applied)
            </label>
          )}
          {coinsUsable && maxCoins > 0 && (
            <div className={`${cardClass} space-y-2`}>
              <label htmlFor="coins-range" className="flex flex-wrap items-baseline justify-between gap-2 font-bold text-on-surface">
                <span>Redeem coins</span>
                <span className="text-sm font-semibold text-on-surface-muted">
                  {coinsToRedeem} of {maxCoins} usable (up to 20% of subtotal) = {formatMoney(coinsValue, currency)}
                </span>
              </label>
              <div className="flex items-center gap-3">
                <input
                  id="coins-range"
                  type="range"
                  min={0}
                  max={maxCoins}
                  step={1}
                  value={coinsToRedeem}
                  onChange={(e) => setCoinsInput(Number(e.target.value))}
                  className="flex-1 accent-[hsl(var(--color-primary))]"
                />
                <input
                  type="number"
                  min={0}
                  max={maxCoins}
                  aria-label="Coins to redeem"
                  value={coinsToRedeem}
                  onChange={(e) => setCoinsInput(Math.min(maxCoins, Math.max(0, Math.floor(Number(e.target.value) || 0))))}
                  className="w-20 rounded-lg border-2 border-ink bg-surface px-2 py-1 text-sm"
                />
              </div>
            </div>
          )}
        </fieldset>
        {lines[0] && <ExchangeOffer productId={lines[0].productId} applied={exchange} onApply={setExchange} />}
      </div>

      <aside className={`${cardClass} h-fit space-y-4`}>
        <h2 className="font-bold text-on-surface">{t('Order summary')}</h2>
        <ul className="divide-y divide-outline">
          {lines.map((line) => (
            <li key={`${line.productId}:${line.variantId ?? ''}`} className="flex gap-3 py-2">
              <div className="h-12 w-12 shrink-0 overflow-hidden rounded-lg bg-surface-variant">
                <ProductImage src={line.image} alt={line.name} productId={line.productId} />
              </div>
              <div className="min-w-0 flex-1 text-sm">
                <p className="line-clamp-2 text-on-surface">{line.name}</p>
                {line.variantLabel && <p className="text-xs text-on-surface-muted">{line.variantLabel}</p>}
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
            <dt className="text-on-surface-muted">{t('Subtotal')}</dt>
            <dd className="text-on-surface">{formatMoney(subtotal, currency)}</dd>
          </div>
          {discount > 0 && (
            <div className="flex justify-between">
              <dt className="text-on-surface-muted">Discount</dt>
              <dd className="text-success">-{formatMoney(discount, currency)}</dd>
            </div>
          )}
          {exchangeValue > 0 && (
            <div className="flex justify-between">
              <dt className="text-on-surface-muted">Exchange value</dt>
              <dd className="text-success">-{formatMoney(exchangeValue, currency)}</dd>
            </div>
          )}
          {coinsValue > 0 && (
            <div className="flex justify-between">
              <dt className="text-on-surface-muted">Coins ({coinsToRedeem})</dt>
              <dd className="text-success">-{formatMoney(coinsValue, currency)}</dd>
            </div>
          )}
          <div className="flex justify-between">
            <dt className="text-on-surface-muted">
              Shipping
              {member && (
                <span className="ml-2 rounded-full border-2 border-ink bg-vivid-amber px-2 py-0.5 text-[10px] font-bold uppercase text-ink">Plus: free</span>
              )}
            </dt>
            <dd className="text-on-surface">{!estimate && !member ? '—' : shipping > 0 ? formatMoney(shipping, currency) : 'Free'}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-on-surface-muted">{currency === 'INR' ? `Includes GST (${GST_RATE}%)` : 'Tax included'}</dt>
            <dd className="text-on-surface">{currency === 'INR' ? `~${formatMoney(estimatedTax, currency)}` : '—'}</dd>
          </div>
          <div className="flex items-baseline justify-between border-t-2 border-outline pt-2">
            <dt className="font-bold text-on-surface">{t('Total')}</dt>
            <dd className="text-xl font-bold text-on-surface">{formatMoney(total, currency)}</dd>
          </div>
          {walletApplied > 0 && (
            <>
              <div className="flex justify-between">
                <dt className="text-on-surface-muted">Paid from wallet</dt>
                <dd className="text-success">-{formatMoney(walletApplied, currency)}</dd>
              </div>
              <div className="flex justify-between font-bold">
                <dt className="text-on-surface">{paymentMethod === 'COD' ? 'Due on delivery' : 'To pay'}</dt>
                <dd className="text-on-surface">{formatMoney(payable, currency)}</dd>
              </div>
            </>
          )}
        </dl>
        <p className="text-xs text-on-surface-muted">Final prices, tax and discounts are confirmed by the store when the order is placed.</p>
        <button
          type="button"
          onClick={() => void place()}
          disabled={placing || !selectedId || showForm || mixedCurrency}
          className={`${primaryButtonClass} w-full`}
        >
          {placing ? t('Placing order...') : paymentMethod === 'CARD' && payable > 0 ? t('Place order and pay') : t('Place order')}
        </button>
      </aside>
    </div>
  )
}
