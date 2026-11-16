import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { PaymentPanel } from '../PaymentPanel'
import type { PaymentConfig, PaymentInfo } from '../../types'

const api = vi.hoisted(() => ({
  config: vi.fn(),
  forOrder: vi.fn(),
  switchProvider: vi.fn(),
  verifyRazorpay: vi.fn(),
  simulate: vi.fn(),
}))
const openCheckout = vi.hoisted(() => vi.fn())

vi.mock('../../services/paymentApi', () => ({ paymentApi: api }))
vi.mock('../../lib/razorpay', () => ({ openRazorpayCheckout: openCheckout }))

const razorpayPayment: PaymentInfo = {
  paymentId: 'pay-1',
  status: 'REQUIRES_PAYMENT',
  mode: 'RAZORPAY',
  publishableKey: 'rzp_test_key',
  providerOrderId: 'order_9',
  amount: 499,
  currency: 'INR',
}

const bothProviders: PaymentConfig = {
  mode: 'STRIPE',
  defaultProvider: 'STRIPE',
  providers: [
    { id: 'STRIPE', label: 'Card', publicKey: 'pk_test', methods: ['card'], currencies: [] },
    { id: 'RAZORPAY', label: 'UPI, cards, netbanking, wallets', publicKey: 'rzp_test_key', methods: ['upi'], currencies: ['INR'] },
  ],
}

describe('PaymentPanel with Razorpay', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.config.mockResolvedValue(bothProviders)
    api.forOrder.mockResolvedValue(razorpayPayment)
  })

  it('offers both providers and marks the current one', async () => {
    render(<PaymentPanel orderId="o-1" payment={razorpayPayment} onSubmitted={() => undefined} />)

    const razorpay = await screen.findByRole('radio', { name: /UPI, cards, netbanking, wallets/ })
    expect(razorpay).toBeChecked()
    expect(screen.getByRole('radio', { name: 'Card' })).not.toBeChecked()
  })

  it('hides the chooser when only one provider is available', async () => {
    api.config.mockResolvedValue({ ...bothProviders, providers: [bothProviders.providers![1]] })
    render(<PaymentPanel orderId="o-1" payment={razorpayPayment} onSubmitted={() => undefined} />)

    await screen.findByRole('button', { name: /Pay/ })
    await waitFor(() => expect(api.config).toHaveBeenCalled())
    expect(screen.queryByRole('radio')).toBeNull()
  })

  it('switching asks the server and shows the new provider', async () => {
    api.switchProvider.mockResolvedValue({
      paymentId: 'pay-1',
      status: 'REQUIRES_PAYMENT',
      mode: 'STRIPE',
      clientSecret: null,
      publishableKey: null,
      currency: 'INR',
    } satisfies PaymentInfo)
    render(<PaymentPanel orderId="o-1" payment={razorpayPayment} onSubmitted={() => undefined} />)

    fireEvent.click(await screen.findByRole('radio', { name: 'Card' }))

    await waitFor(() => expect(api.switchProvider).toHaveBeenCalledWith('o-1', 'STRIPE'))
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Card' })).toBeChecked())
  })

  it('a successful Razorpay payment is verified on the server, then reported as submitted', async () => {
    const onSubmitted = vi.fn()
    api.verifyRazorpay.mockResolvedValue({ ...razorpayPayment, status: 'SUCCEEDED' })
    openCheckout.mockImplementation(async (args) => {
      args.onSuccess({
        razorpay_order_id: 'order_9',
        razorpay_payment_id: 'pay_x',
        razorpay_signature: 'sig',
      })
    })
    render(<PaymentPanel orderId="o-1" payment={razorpayPayment} onSubmitted={onSubmitted} />)

    fireEvent.click(await screen.findByRole('button', { name: /Pay/ }))

    await waitFor(() => expect(onSubmitted).toHaveBeenCalled())
    expect(openCheckout).toHaveBeenCalledWith(expect.objectContaining({ keyId: 'rzp_test_key', orderId: 'order_9' }))
    expect(api.verifyRazorpay).toHaveBeenCalledWith('pay-1', {
      razorpayOrderId: 'order_9',
      razorpayPaymentId: 'pay_x',
      razorpaySignature: 'sig',
    })
  })

  it('if verification fails after paying, it does not claim success and offers a status check', async () => {
    const onSubmitted = vi.fn()
    api.verifyRazorpay.mockRejectedValue(new Error('network'))
    openCheckout.mockImplementation(async (args) => {
      args.onSuccess({ razorpay_order_id: 'order_9', razorpay_payment_id: 'pay_x', razorpay_signature: 'sig' })
    })
    render(<PaymentPanel orderId="o-1" payment={razorpayPayment} onSubmitted={onSubmitted} />)

    fireEvent.click(await screen.findByRole('button', { name: /Pay/ }))

    expect(await screen.findByText(/could not confirm your payment/i)).toBeTruthy()
    expect(onSubmitted).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Check order status/ }))
    expect(onSubmitted).toHaveBeenCalled()
  })
})
