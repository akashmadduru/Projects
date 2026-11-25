/**
 * Pays for a CARD order. Stripe and Razorpay can both be on; when they are, the customer picks one
 * and can switch until they pay. Stripe renders the Stripe Payment Element; Razorpay opens its
 * checkout window (UPI, cards, netbanking, wallets) and the server then verifies the signature.
 * SIMULATED mode (no provider configured, dev only) renders a themed test panel.
 * `onSubmitted` fires once the payment was submitted; the caller then lands on the order page,
 * which polls until the order leaves PENDING_PAYMENT.
 */

import { Elements, PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js'
import { loadStripe } from '@stripe/stripe-js'
import type { Appearance, Stripe } from '@stripe/stripe-js'
import { useEffect, useMemo, useState } from 'react'
import { ErrorAlert } from './ErrorAlert'
import { cardClass, primaryButtonClass, secondaryButtonClass } from './Form'
import { formatMoney } from '../lib/format'
import { errorMessage } from '../lib/errors'
import { loadRazorpay, openRazorpayCheckout } from '../lib/razorpay'
import { paymentApi } from '../services/paymentApi'
import type { PaymentConfig, PaymentInfo, PaymentProvider } from '../types'

interface Props {
  orderId: string
  /** Where Stripe returns after a redirect-based payment; defaults to the order page. */
  returnUrl?: string
  payment: PaymentInfo
  onSubmitted: () => void
}

const stripeCache = new Map<string, Promise<Stripe | null>>()

function stripeFor(publishableKey: string): Promise<Stripe | null> {
  let promise = stripeCache.get(publishableKey)
  if (!promise) {
    promise = loadStripe(publishableKey)
    stripeCache.set(publishableKey, promise)
  }
  return promise
}

/** Reads a space-separated HSL design token (e.g. "210 33% 17%") into a CSS colour Stripe accepts. */
function tokenColor(name: string, fallback: string): string {
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  const match = /^(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?%)\s+(\d+(?:\.\d+)?%)$/.exec(raw)
  return match ? `hsl(${match[1]}, ${match[2]}, ${match[3]})` : fallback
}

function appearance(): Appearance {
  const ink = tokenColor('--color-ink', '#1d2b3a')
  return {
    theme: 'stripe',
    variables: {
      colorPrimary: tokenColor('--color-primary', '#1b7469'),
      colorText: ink,
      borderRadius: '10px',
      fontFamily: 'inherit',
    },
    rules: { '.Input': { border: `2px solid ${ink}`, boxShadow: 'none' } },
  }
}

function StripeForm({ orderId, returnUrl, onSubmitted }: { orderId: string; returnUrl?: string; onSubmitted: () => void }) {
  const stripe = useStripe()
  const elements = useElements()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const pay = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!stripe || !elements) return
    setBusy(true)
    setError(undefined)
    const result = await stripe.confirmPayment({
      elements,
      confirmParams: { return_url: returnUrl ?? `${window.location.origin}/orders/${orderId}?payment=return` },
      redirect: 'if_required',
    })
    if (result.error) {
      setError(result.error.message ?? 'Your payment could not be processed.')
      setBusy(false)
      return
    }
    onSubmitted()
  }

  return (
    <form onSubmit={(e) => void pay(e)} className="space-y-4">
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <PaymentElement />
      <button type="submit" disabled={busy || !stripe || !elements} className={`${primaryButtonClass} w-full`}>
        {busy ? 'Processing...' : 'Pay now'}
      </button>
    </form>
  )
}

function SimulatedForm({ payment, onSubmitted }: { payment: PaymentInfo; onSubmitted: () => void }) {
  const [busy, setBusy] = useState<'SUCCEEDED' | 'FAILED' | null>(null)
  const [error, setError] = useState<string | undefined>()

  const run = async (outcome: 'SUCCEEDED' | 'FAILED') => {
    setBusy(outcome)
    setError(undefined)
    try {
      await paymentApi.simulate(payment.paymentId, outcome)
      onSubmitted()
    } catch (err) {
      setError(errorMessage(err))
      setBusy(null)
    }
  }

  return (
    <div className="space-y-4">
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <p className="text-sm text-on-surface-muted">
        Payments are running in test mode, so no real card is charged. Pick the outcome you want to try.
      </p>
      <div className="flex flex-wrap gap-3">
        <button type="button" disabled={busy !== null} onClick={() => void run('SUCCEEDED')} className={primaryButtonClass}>
          {busy === 'SUCCEEDED' ? 'Paying...' : 'Pay'}
        </button>
        <button
          type="button"
          disabled={busy !== null}
          onClick={() => void run('FAILED')}
          className={`${secondaryButtonClass} text-error`}
        >
          {busy === 'FAILED' ? 'Declining...' : 'Decline'}
        </button>
      </div>
    </div>
  )
}

function RazorpayForm({ payment, onSubmitted }: { payment: PaymentInfo; onSubmitted: () => void }) {
  const [busy, setBusy] = useState(false)
  const [unconfirmed, setUnconfirmed] = useState(false)
  const [error, setError] = useState<string | undefined>()

  // Start fetching checkout.js as soon as Razorpay is the chosen provider, so "Pay" opens the window
  // without waiting on the network. A failure here is silent; pay() loads again and reports it.
  useEffect(() => {
    void loadRazorpay().catch(() => undefined)
  }, [])

  const pay = async () => {
    if (!payment.publishableKey || !payment.providerOrderId) return
    setBusy(true)
    setError(undefined)
    try {
      await openRazorpayCheckout({
        keyId: payment.publishableKey,
        orderId: payment.providerOrderId,
        name: 'Checkout',
        onDismiss: () => setBusy(false),
        onFailure: (message) => setError(message),
        onSuccess: (response) => {
          setError(undefined)
          paymentApi
            .verifyRazorpay(payment.paymentId, {
              razorpayOrderId: response.razorpay_order_id,
              razorpayPaymentId: response.razorpay_payment_id,
              razorpaySignature: response.razorpay_signature,
            })
            .then(onSubmitted)
            .catch(() => {
              // The money may have moved even though we could not confirm it; the server also
              // learns of it through the webhook, so send the customer to the order page.
              setUnconfirmed(true)
              setBusy(false)
            })
        },
      })
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  if (unconfirmed) {
    return (
      <div className="space-y-3">
        <p className="text-sm text-on-surface">
          We could not confirm your payment yet. If you were charged, your order updates within a minute.
        </p>
        <button type="button" onClick={onSubmitted} className={primaryButtonClass}>
          Check order status
        </button>
      </div>
    )
  }

  const label =
    payment.amount !== undefined && payment.currency ? `Pay ${formatMoney(payment.amount, payment.currency)}` : 'Pay now'

  return (
    <div className="space-y-4">
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <p className="text-sm text-on-surface-muted">Pay with UPI, cards, netbanking or a wallet in a secure Razorpay window.</p>
      <button
        type="button"
        disabled={busy || !payment.providerOrderId}
        onClick={() => void pay()}
        className={`${primaryButtonClass} w-full`}
      >
        {busy ? 'Waiting for payment...' : label}
      </button>
    </div>
  )
}

function ProviderChooser({
  config,
  current,
  currency,
  busy,
  onPick,
}: {
  config: PaymentConfig
  current: PaymentProvider
  currency?: string
  busy: boolean
  onPick: (provider: PaymentProvider) => void
}) {
  const options = (config.providers ?? []).filter(
    (p) => !currency || p.currencies.length === 0 || p.currencies.includes(currency),
  )
  if (options.length < 2) return null
  return (
    <fieldset className="space-y-2" disabled={busy}>
      <legend className="text-sm font-bold text-on-surface">Pay with</legend>
      <div className="flex flex-wrap gap-2">
        {options.map((p) => (
          <label
            key={p.id}
            className={`flex cursor-pointer items-center gap-2 rounded-lg border-2 border-ink px-3 py-2 text-sm font-semibold ${
              current === p.id ? 'bg-surface-variant' : 'bg-surface'
            }`}
          >
            <input
              type="radio"
              name="payment-provider"
              value={p.id}
              checked={current === p.id}
              onChange={() => onPick(p.id)}
            />
            {p.label}
          </label>
        ))}
      </div>
    </fieldset>
  )
}

export function PaymentPanel({ orderId, returnUrl, payment: initial, onSubmitted }: Props) {
  const [payment, setPayment] = useState<PaymentInfo>(initial)
  const [config, setConfig] = useState<PaymentConfig | null>(null)
  const [switching, setSwitching] = useState(false)
  const [error, setError] = useState<string | undefined>()

  useEffect(() => setPayment(initial), [initial])

  // The order page hands over only a short payment block; load the full record (currency, the
  // provider order id) and the list of providers the customer may choose from.
  useEffect(() => {
    if (initial.mode === 'SIMULATED') return
    let cancelled = false
    void Promise.all([paymentApi.config(), paymentApi.forOrder(orderId)])
      .then(([cfg, full]) => {
        if (cancelled) return
        setConfig(cfg)
        setPayment((prev) => ({ ...prev, ...full }))
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [orderId, initial.mode])

  const pick = async (provider: PaymentProvider) => {
    if (provider === payment.mode) return
    setSwitching(true)
    setError(undefined)
    try {
      const next = await paymentApi.switchProvider(orderId, provider)
      setPayment((prev) => ({ ...prev, ...next }))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSwitching(false)
    }
  }

  const stripeReady = payment.mode === 'STRIPE' && !!payment.clientSecret && !!payment.publishableKey
  const razorpayReady = payment.mode === 'RAZORPAY' && !!payment.providerOrderId && !!payment.publishableKey
  const options = useMemo(
    () => ({ clientSecret: payment.clientSecret ?? '', appearance: appearance() }),
    [payment.clientSecret],
  )

  return (
    <section className={`${cardClass} space-y-4`} aria-labelledby="payment-heading">
      <h2 id="payment-heading" className="font-bold text-on-surface">
        {payment.mode === 'SIMULATED' ? 'Test payment' : 'Pay online'}
      </h2>
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      {config && payment.mode !== 'SIMULATED' && payment.status === 'REQUIRES_PAYMENT' && (
        <ProviderChooser
          config={config}
          current={payment.mode}
          currency={payment.currency}
          busy={switching}
          onPick={(p) => void pick(p)}
        />
      )}
      {switching ? (
        <p className="text-sm text-on-surface-muted">Switching payment method...</p>
      ) : stripeReady ? (
        <Elements key={payment.clientSecret} stripe={stripeFor(payment.publishableKey as string)} options={options}>
          <StripeForm orderId={orderId} returnUrl={returnUrl} onSubmitted={onSubmitted} />
        </Elements>
      ) : razorpayReady ? (
        <RazorpayForm payment={payment} onSubmitted={onSubmitted} />
      ) : payment.mode === 'SIMULATED' ? (
        <SimulatedForm payment={payment} onSubmitted={onSubmitted} />
      ) : (
        <ErrorAlert message="Online payment is not available right now. Please try again shortly." />
      )}
    </section>
  )
}
