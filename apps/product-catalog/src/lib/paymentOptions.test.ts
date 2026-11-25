import { describe, expect, it } from 'vitest'
import { onlinePaymentSummary, providersFor } from './paymentOptions'
import type { PaymentConfig } from '../types'

const both: PaymentConfig = {
  mode: 'STRIPE',
  providers: [
    { id: 'STRIPE', label: 'Card (Stripe)', publicKey: 'pk', methods: ['card'], currencies: [] },
    { id: 'RAZORPAY', label: 'Razorpay', publicKey: 'rzp', methods: ['upi', 'card', 'netbanking', 'wallet'], currencies: ['INR'] },
  ],
  defaultProvider: 'STRIPE',
}

describe('providersFor', () => {
  it('keeps any-currency providers and those listing the currency', () => {
    expect(providersFor(both, 'inr').map((p) => p.id)).toEqual(['STRIPE', 'RAZORPAY'])
    expect(providersFor(both, 'USD').map((p) => p.id)).toEqual(['STRIPE'])
  })

  it('returns nothing without config', () => {
    expect(providersFor(null, 'INR')).toEqual([])
  })
})

describe('onlinePaymentSummary', () => {
  it('merges methods across providers without duplicates', () => {
    expect(onlinePaymentSummary(both, 'INR')).toEqual({
      title: 'Pay online',
      description: 'UPI, cards, netbanking or wallets',
    })
  })

  it('drops INR-only providers for other currencies', () => {
    expect(onlinePaymentSummary(both, 'USD').description).toBe('Cards')
  })

  it('falls back to a neutral line in SIMULATED mode or before config loads', () => {
    expect(onlinePaymentSummary({ mode: 'SIMULATED', providers: [] }, 'INR').description).toBe('Pay securely online')
    expect(onlinePaymentSummary(null, 'INR').description).toBe('Pay securely online')
  })
})
