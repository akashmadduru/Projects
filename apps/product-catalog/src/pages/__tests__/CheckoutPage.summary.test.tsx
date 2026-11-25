/**
 * CheckoutPage part 1 (TD-TEST-02): first-address default, order summary lines and totals, empty
 * and unavailable states. The service modules are mocked (./checkoutMocks); PaymentPanel and
 * ExchangeOffer have their own coverage and are stubbed.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('../../services/userApi', async () => (await import('./checkoutMocks')).userApiModule())
vi.mock('../../services/orderApi', async () => (await import('./checkoutMocks')).orderApiModule())
vi.mock('../../services/paymentApi', async () => (await import('./checkoutMocks')).paymentApiModule())
vi.mock('../../services/engageApi', async () => (await import('./checkoutMocks')).engageApiModule())
vi.mock('../../services/productApi', async () => (await import('./checkoutMocks')).productApiModule())
vi.mock('../../components/ExchangeOffer', () => ({ ExchangeOffer: () => null }))
vi.mock('../../components/PaymentPanel', () => ({ PaymentPanel: () => null }))

import { api } from './checkoutMocks'
import {
  coins,
  estimate,
  problem,
  ready,
  radio,
  renderCheckout,
  resetApis,
  resetCart,
  seedApis,
  seedCart,
  summaryValue,
  wallet,
} from './checkoutTestUtils'

const TWO_LINES = [
  { productId: 'p1', name: 'Blue Shirt', price: 500, qty: 2 },
  { productId: 'p2', name: 'Steel Mug', price: 250, qty: 1 },
]

beforeEach(() => {
  resetApis()
  seedApis()
  seedCart(TWO_LINES)
})

afterEach(() => {
  cleanup()
  resetApis()
  resetCart()
  vi.restoreAllMocks()
})

describe('address and order summary', () => {
  it('preselects the first address and lists each line with Qty and line total', async () => {
    renderCheckout()
    await ready()

    expect(radio(/Home/).checked).toBe(true)
    expect(radio(/Office/).checked).toBe(false)

    const aside = within(screen.getByRole('complementary'))
    const shirt = aside.getByText('Blue Shirt').closest('li')!
    expect(shirt).toHaveTextContent('Qty 2')
    expect(shirt).toHaveTextContent('₹1,000.00')
    const mug = aside.getByText('Steel Mug').closest('li')!
    expect(mug).toHaveTextContent('Qty 1')
    expect(mug).toHaveTextContent('₹250.00')
  })

  it('Subtotal is the sum of price x quantity and the estimate is requested for it', async () => {
    renderCheckout()
    await ready()

    expect(summaryValue('Subtotal')).toBe('₹1,250.00')
    expect(api.deliveryEstimate).toHaveBeenCalledWith('560001', 1250, 'INR')
    expect(api.availableCoupons).toHaveBeenCalledWith(1250, 'INR')
  })

  it('re-requests the estimate for another address and ignores the stale first answer', async () => {
    let resolveFirst!: (v: ReturnType<typeof estimate>) => void
    api.deliveryEstimate
      .mockImplementationOnce(() => new Promise((res) => (resolveFirst = res)))
      .mockResolvedValueOnce(estimate({ pincode: '110001', shippingFee: 90 }))
    const user = userEvent.setup()
    renderCheckout()
    await screen.findByRole('radio', { name: /Office/ })

    await user.click(radio(/Office/))
    await waitFor(() => expect(summaryValue('Shipping')).toBe('₹90.00'))

    // the answer for the previously selected pincode arrives late and must not overwrite it
    await act(async () => {
      resolveFirst(estimate({ pincode: '560001', shippingFee: 40 }))
    })
    expect(summaryValue('Shipping')).toBe('₹90.00')
    expect(api.deliveryEstimate).toHaveBeenCalledWith('110001', 1250, 'INR')
  })

  it('shows a cart line of a variant with its label', async () => {
    seedCart([{ productId: 'p1', name: 'Blue Shirt', price: 500, qty: 1, variantId: 'v1', variantLabel: 'Red / M' }])
    renderCheckout()
    await ready()

    expect(within(screen.getByRole('complementary')).getByText('Red / M')).toBeInTheDocument()
  })

  it('skips cart lines that have no snapshot', async () => {
    seedCart([{ productId: 'p1', name: 'Blue Shirt', price: 500, qty: 1 }])
    const { useCartStore } = await import('../../stores')
    useCartStore.setState({
      items: [...useCartStore.getState().items, { productId: 'ghost', quantity: 5, addedAt: 9 }],
    })
    renderCheckout()
    await ready()

    expect(summaryValue('Subtotal')).toBe('₹500.00')
    expect(screen.queryByText('Qty 5')).not.toBeInTheDocument()
  })
})

describe('empty and unavailable', () => {
  it('shows an empty cart with a Continue shopping link and no address list', async () => {
    seedCart([])
    renderCheckout()

    expect(await screen.findByText('Your cart is empty')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Continue shopping' })).toHaveAttribute('href', '/')
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.queryByText('Shipping address')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /place order/i })).not.toBeInTheDocument()
  })

  it('shows "That product is not available" when the buyNow product fails to load', async () => {
    api.productDetail.mockRejectedValue(problem(404, 'NOT_FOUND', {}, 'No such product'))
    renderCheckout('/checkout?buyNow=missing')

    expect(await screen.findByText('That product is not available')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Continue shopping' })).toHaveAttribute('href', '/')
    expect(screen.queryByText('Your cart is empty')).not.toBeInTheDocument()
    expect(api.productDetail).toHaveBeenCalledWith('missing')
  })

  it('shows a loading state while the buyNow product loads, then the product line', async () => {
    renderCheckout('/checkout?buyNow=bn1&qty=3')

    expect(screen.getByRole('status', { name: 'Loading product' })).toBeInTheDocument()
    await ready()
    const aside = within(screen.getByRole('complementary'))
    expect(aside.getByText('Buy Now Lamp')).toBeInTheDocument()
    expect(aside.getByText('Qty 3')).toBeInTheDocument()
    expect(summaryValue('Subtotal')).toBe('₹900.00')
  })
})

describe('discount, coins, shipping and total', () => {
  const applyCoupon = async (code = 'SAVE100') => {
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Have a coupon code?'), code)
    await user.click(screen.getByRole('button', { name: 'Apply' }))
  }

  it('shows the server discount, coins and shipping, and Total = subtotal - discount - coins + shipping', async () => {
    api.validateCoupon.mockResolvedValue({ code: 'SAVE100', description: null, discountAmount: 100, currency: 'INR' })
    api.coins.mockResolvedValue(coins(500))
    renderCheckout()
    await ready()

    await applyCoupon()
    await waitFor(() => expect(summaryValue('Discount')).toBe('-₹100.00'))
    expect(api.validateCoupon).toHaveBeenCalledWith('SAVE100', 1250, 'INR')

    // usable coins are capped at 20% of (subtotal - discount) = 230
    const input = await screen.findByRole('spinbutton', { name: 'Coins to redeem' })
    fireEvent.change(input, { target: { value: '100' } })
    await waitFor(() => expect(summaryValue(/^Coins/)).toBe('-₹100.00'))

    expect(summaryValue('Shipping')).toBe('₹40.00')
    expect(summaryValue('Total')).toBe('₹1,090.00') // 1250 - 100 - 100 + 40
  })

  it('caps redeemable coins at 20% of the subtotal', async () => {
    api.coins.mockResolvedValue(coins(5000))
    renderCheckout()
    await ready()

    const input = await screen.findByRole('spinbutton', { name: 'Coins to redeem' })
    fireEvent.change(input, { target: { value: '5000' } })
    await waitFor(() => expect(summaryValue(/^Coins/)).toBe('-₹250.00'))
    expect(summaryValue('Total')).toBe('₹1,040.00') // 1250 - 250 + 40
  })

  it('never lets the total go below zero when the discount exceeds the subtotal', async () => {
    api.validateCoupon.mockResolvedValue({ code: 'HUGE', description: null, discountAmount: 5000, currency: 'INR' })
    api.deliveryEstimate.mockResolvedValue(estimate({ shippingFee: 0 }))
    renderCheckout()
    await ready()

    await applyCoupon('HUGE')
    await waitFor(() => expect(summaryValue('Discount')).toBe('-₹5,000.00'))
    expect(summaryValue('Total')).toBe('₹0.00')
  })

  it('shows Free shipping when the estimate fee is zero', async () => {
    api.deliveryEstimate.mockResolvedValue(estimate({ shippingFee: 0 }))
    renderCheckout()
    await ready()

    expect(summaryValue('Shipping')).toBe('Free')
    expect(summaryValue('Total')).toBe('₹1,250.00')
  })

  it('shows Shipping Free for a Plus member regardless of the estimate fee', async () => {
    api.membership.mockResolvedValue({ active: true, plan: 'PLUS', endsAt: null, benefits: [], plans: [] })
    renderCheckout()
    await ready()

    await waitFor(() => expect(summaryValue(/^Shipping/)).toBe('Free'))
    expect(screen.getByText('Plus: free')).toBeInTheDocument()
    expect(summaryValue('Total')).toBe('₹1,250.00')
  })

  it('shows an "Includes GST (18%)" line for an INR order', async () => {
    renderCheckout()
    await ready()

    // total 1290 includes 18% GST: 1290 * 18 / 118
    expect(summaryValue('Includes GST (18%)')).toBe('~₹196.78')
    expect(screen.queryByText('Tax included')).not.toBeInTheDocument()
  })

  it('shows "Tax included" instead of GST for a non-INR order', async () => {
    seedCart([{ productId: 'p1', name: 'Blue Shirt', price: 20, qty: 2, currency: 'USD' }])
    api.deliveryEstimate.mockResolvedValue(estimate({ currency: 'USD', shippingFee: 5 }))
    renderCheckout()
    await ready()

    expect(summaryValue('Tax included')).toBe('—')
    expect(screen.queryByText(/Includes GST/)).not.toBeInTheDocument()
    expect(summaryValue('Subtotal')).toBe('$40.00')
    expect(summaryValue('Total')).toBe('$45.00')
    expect(api.deliveryEstimate).toHaveBeenCalledWith('560001', 40, 'USD')
  })

  it('does not show a wallet or coin row when the services return empty balances', async () => {
    api.wallet.mockResolvedValue(wallet(0))
    renderCheckout()
    await ready()

    expect(screen.queryByText(/Paid from wallet/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Coins/, { selector: 'dt' })).not.toBeInTheDocument()
  })
})
