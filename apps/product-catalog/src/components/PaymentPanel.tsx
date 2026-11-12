/**
 * Pays for a CARD order. Stripe mode renders the Stripe Payment Element (needs clientSecret and
 * publishableKey); SIMULATED mode (no Stripe keys configured) renders a themed test panel.
 * `onSubmitted` fires once the payment was submitted; the caller then lands on the order page,
 * which polls until the order leaves PENDING_PAYMENT.
 */

import { Elements, PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js'
import { loadStripe } from '@stripe/stripe-js'
import type { Appearance, Stripe } from '@stripe/stripe-js'
import { useMemo, useState } from 'react'
import { ErrorAlert } from './ErrorAlert'
import { cardClass, primaryButtonClass, secondaryButtonClass } from './Form'
import { errorMessage } from '../lib/errors'
import { paymentApi } from '../services/paymentApi'
import type { PaymentInfo } from '../types'

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

export function PaymentPanel({ orderId, returnUrl, payment, onSubmitted }: Props) {
  const stripeReady = payment.mode === 'STRIPE' && !!payment.clientSecret && !!payment.publishableKey
  const options = useMemo(
    () => ({ clientSecret: payment.clientSecret ?? '', appearance: appearance() }),
    [payment.clientSecret],
  )

  return (
    <section className={`${cardClass} space-y-4`} aria-labelledby="payment-heading">
      <h2 id="payment-heading" className="font-bold text-on-surface">
        {payment.mode === 'SIMULATED' ? 'Test payment' : 'Pay by card'}
      </h2>
      {stripeReady ? (
        <Elements stripe={stripeFor(payment.publishableKey as string)} options={options}>
          <StripeForm orderId={orderId} returnUrl={returnUrl} onSubmitted={onSubmitted} />
        </Elements>
      ) : payment.mode === 'SIMULATED' ? (
        <SimulatedForm payment={payment} onSubmitted={onSubmitted} />
      ) : (
        <ErrorAlert message="Card payment is not available right now. Please try again shortly." />
      )}
    </section>
  )
}
