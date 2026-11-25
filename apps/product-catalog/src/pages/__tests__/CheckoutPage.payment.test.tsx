/**
 * CheckoutPage part 2 (TD-TEST-02): payment method choices (card, COD rules, wallet), disabled
 * states, and optional side calls that fail. Place payloads are covered in CheckoutPage.place.test.tsx.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, screen, waitFor } from '@testing-library/react'
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
  placeButton,
  problem,
  radio,
  ready,
  renderCheckout,
  resetApis,
  resetCart,
  seedApis,
  seedCart,
  summaryValue,
  wallet,
} from './checkoutTestUtils'

// Subtotal 1250 + shipping 40 = total 1290 (INR)
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

describe('wallet', () => {
  it('lets the Wallet method be chosen when the balance covers the whole total', async () => {
    api.wallet.mockResolvedValue(wallet(5000))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    const walletRadio = await screen.findByRole('radio', { name: /Wallet/ })
    expect(walletRadio).toBeEnabled()
    expect(screen.getByText('Pay in full from your ₹5,000.00 balance')).toBeInTheDocument()
    // a covering balance has no partial-use checkbox
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()

    await user.click(walletRadio)
    expect(radio(/Wallet/).checked).toBe(true)
    expect(radio(/Pay online/).checked).toBe(false)
    expect(summaryValue('Paid from wallet')).toBe('-₹1,290.00')
    expect(summaryValue('To pay')).toBe('₹0.00')
  })

  it('disables the Wallet radio for a partial balance and offers the use-wallet checkbox', async () => {
    api.wallet.mockResolvedValue(wallet(500))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    const walletRadio = await screen.findByRole('radio', { name: /Wallet/ })
    expect(walletRadio).toBeDisabled()
    expect(screen.getByText('Balance ₹500.00 does not cover this order')).toBeInTheDocument()
    expect(screen.queryByText('Paid from wallet')).not.toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: /Use wallet balance/ }))
    expect(summaryValue('Paid from wallet')).toBe('-₹500.00')
    expect(summaryValue('To pay')).toBe('₹790.00')
    expect(radio(/Pay online/).checked).toBe(true)
  })

  it('labels the remainder "Due on delivery" when the method is COD', async () => {
    api.wallet.mockResolvedValue(wallet(500))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(radio(/Cash on Delivery/))
    await user.click(await screen.findByRole('checkbox', { name: /Use wallet balance/ }))
    expect(summaryValue('Paid from wallet')).toBe('-₹500.00')
    expect(summaryValue('Due on delivery')).toBe('₹790.00')
    expect(screen.queryByText('To pay')).not.toBeInTheDocument()
  })

  it('does not offer a wallet held in a different currency', async () => {
    api.wallet.mockResolvedValue(wallet(5000, 'USD'))
    renderCheckout()
    await ready()
    await waitFor(() => expect(api.wallet).toHaveBeenCalled())

    expect(screen.queryByRole('radio', { name: /Wallet/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('does not offer an empty wallet', async () => {
    api.wallet.mockResolvedValue(wallet(0))
    renderCheckout()
    await ready()

    expect(screen.queryByRole('radio', { name: /Wallet/ })).not.toBeInTheDocument()
  })
})

describe('Cash on Delivery rules', () => {
  const codReason = () => document.getElementById('cod-reason')?.textContent

  it('is available when the estimate allows it and the total is within the limit', async () => {
    renderCheckout()
    await ready()

    expect(radio(/Cash on Delivery/)).toBeEnabled()
    expect(codReason()).toBe('Pay when your order arrives')
  })

  it('is disabled with a reason when the estimate says COD is not available', async () => {
    api.deliveryEstimate.mockResolvedValue(estimate({ codAvailable: false }))
    renderCheckout()
    await ready()

    expect(radio(/Cash on Delivery/)).toBeDisabled()
    expect(codReason()).toBe('Cash on Delivery is not available for this address.')
  })

  it('is disabled with a reason while there is no estimate', async () => {
    api.deliveryEstimate.mockImplementation(() => new Promise(() => undefined))
    renderCheckout()
    await screen.findByRole('radio', { name: /Home/ })

    expect(radio(/Cash on Delivery/)).toBeDisabled()
    expect(codReason()).toBe('Choose a deliverable address to check Cash on Delivery.')
  })

  it('is disabled above 50000 for INR', async () => {
    seedCart([{ productId: 'p1', name: 'TV', price: 60000, qty: 1 }])
    renderCheckout()
    await ready()

    expect(radio(/Cash on Delivery/)).toBeDisabled()
    expect(codReason()).toBe('Cash on Delivery is available up to ₹50,000.00.')
  })

  it('stays available right at the INR limit', async () => {
    seedCart([{ productId: 'p1', name: 'TV', price: 49960, qty: 1 }]) // + 40 shipping = 50000
    renderCheckout()
    await ready()

    expect(summaryValue('Total')).toBe('₹50,000.00')
    expect(radio(/Cash on Delivery/)).toBeEnabled()
  })

  it('is disabled above 1000 for other currencies', async () => {
    seedCart([{ productId: 'p1', name: 'Laptop', price: 1200, qty: 1, currency: 'USD' }])
    api.deliveryEstimate.mockResolvedValue(estimate({ currency: 'USD', shippingFee: 0 }))
    renderCheckout()
    await ready()

    expect(radio(/Cash on Delivery/)).toBeDisabled()
    expect(codReason()).toBe('Cash on Delivery is available up to $1,000.00.')
  })

  it('reverts to CARD when COD was selected and becomes unavailable', async () => {
    api.deliveryEstimate
      .mockResolvedValueOnce(estimate({ codAvailable: true }))
      .mockResolvedValueOnce(estimate({ pincode: '110001', codAvailable: false }))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(radio(/Cash on Delivery/))
    expect(radio(/Cash on Delivery/).checked).toBe(true)

    await user.click(radio(/Office/))
    await waitFor(() => expect(radio(/Cash on Delivery/)).toBeDisabled())
    expect(radio(/Cash on Delivery/).checked).toBe(false)
    expect(radio(/Pay online/).checked).toBe(true)
  })
})

describe('disabled place button and error states', () => {
  it('shows the address form and disables placing when there are no saved addresses', async () => {
    api.listAddresses.mockResolvedValue([])
    renderCheckout()

    expect(await screen.findByRole('button', { name: 'Save and use this address' })).toBeInTheDocument()
    expect(placeButton()).toBeDisabled()
    expect(screen.queryByRole('radio', { name: /Home/ })).not.toBeInTheDocument()
    expect(api.deliveryEstimate).not.toHaveBeenCalled()
  })

  it('disables placing while the add-address form is open, and enables it again on cancel', async () => {
    const user = userEvent.setup()
    renderCheckout()
    await ready()
    expect(placeButton()).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Add a new address' }))
    expect(placeButton()).toBeDisabled()

    await user.click(screen.getByRole('button', { name: /cancel/i }))
    expect(placeButton()).toBeEnabled()
  })

  it('shows an error alert and no place button when the address load fails', async () => {
    api.listAddresses.mockRejectedValue(problem(500, 'SERVER_ERROR', {}, 'Addresses are down'))
    renderCheckout()

    expect(await screen.findByRole('alert')).toHaveTextContent('Addresses are down')
    expect(screen.queryByRole('button', { name: /place order/i })).not.toBeInTheDocument()
  })

  it('shows an error alert and disables placing for mixed currencies in the cart', async () => {
    seedCart([
      { productId: 'p1', name: 'Blue Shirt', price: 500, qty: 1 },
      { productId: 'p2', name: 'Steel Mug', price: 10, qty: 1, currency: 'USD' },
    ])
    renderCheckout()
    await ready()

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Items in your cart use different currencies and cannot be ordered together.',
    )
    expect(placeButton()).toBeDisabled()
  })

  it('shows the estimate error, Shipping "—" and a disabled COD when the estimate fails', async () => {
    api.deliveryEstimate.mockRejectedValue(problem(503, 'DELIVERY_DOWN', {}, 'Delivery check unavailable'))
    renderCheckout()
    await screen.findByText('Delivery check unavailable')

    expect(summaryValue('Shipping')).toBe('—')
    expect(radio(/Cash on Delivery/)).toBeDisabled()
    expect(document.getElementById('cod-reason')).toHaveTextContent(
      'Choose a deliverable address to check Cash on Delivery.',
    )
    expect(placeButton()).toBeEnabled() // the estimate is advisory; checkout still works
  })
})

describe('optional services that fail hide only their own controls', () => {
  const placeableAndQuiet = async () => {
    expect(placeButton()).toBeEnabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(radio(/Pay online/)).toBeEnabled()
    expect(summaryValue('Subtotal')).toBe('₹1,250.00')
  }

  it('wallet', async () => {
    api.wallet.mockResolvedValue(wallet(5000))
    const { unmount } = renderCheckout()
    await ready()
    expect(await screen.findByRole('radio', { name: /Wallet/ })).toBeInTheDocument()
    unmount()

    api.wallet.mockRejectedValue(new Error('down'))
    renderCheckout()
    await ready()
    await waitFor(() => expect(api.wallet).toHaveBeenCalledTimes(2))

    expect(screen.queryByRole('radio', { name: /Wallet/ })).not.toBeInTheDocument()
    await placeableAndQuiet()
  })

  it('coins', async () => {
    api.coins.mockResolvedValue(coins(500))
    const { unmount } = renderCheckout()
    await ready()
    expect(await screen.findByText('Redeem coins')).toBeInTheDocument()
    unmount()

    api.coins.mockRejectedValue(new Error('down'))
    renderCheckout()
    await ready()
    await waitFor(() => expect(api.coins).toHaveBeenCalledTimes(2))

    expect(screen.queryByText('Redeem coins')).not.toBeInTheDocument()
    await placeableAndQuiet()
  })

  it('membership', async () => {
    api.membership.mockResolvedValue({ active: true, plan: 'PLUS', endsAt: null, benefits: [], plans: [] })
    const { unmount } = renderCheckout()
    await ready()
    expect(await screen.findByText('Plus: free')).toBeInTheDocument()
    unmount()

    api.membership.mockRejectedValue(new Error('down'))
    renderCheckout()
    await ready()
    await waitFor(() => expect(api.membership).toHaveBeenCalledTimes(2))

    expect(screen.queryByText('Plus: free')).not.toBeInTheDocument()
    expect(summaryValue('Shipping')).toBe('₹40.00')
    await placeableAndQuiet()
  })

  it('payment config keeps the neutral online-payment wording', async () => {
    api.paymentConfig.mockResolvedValue({
      mode: 'RAZORPAY',
      providers: [{ id: 'RAZORPAY', label: 'Razorpay', publicKey: 'k', methods: ['upi', 'card'], currencies: ['INR'] }],
    })
    const { unmount } = renderCheckout()
    await ready()
    expect(await screen.findByText('UPI or cards')).toBeInTheDocument()
    unmount()

    api.paymentConfig.mockRejectedValue(new Error('down'))
    renderCheckout()
    await ready()
    await waitFor(() => expect(api.paymentConfig).toHaveBeenCalledTimes(2))

    expect(screen.getByText('Pay securely online')).toBeInTheDocument()
    await placeableAndQuiet()
  })

  it('available coupons', async () => {
    api.availableCoupons.mockResolvedValue([
      { code: 'SAVE50', description: 'Save fifty', discountAmount: 50, minOrderAmount: null },
    ])
    const { unmount } = renderCheckout()
    await ready()
    expect(await screen.findByRole('list', { name: 'Suggested coupons' })).toBeInTheDocument()
    unmount()

    api.availableCoupons.mockRejectedValue(new Error('down'))
    renderCheckout()
    await ready()
    await waitFor(() => expect(api.availableCoupons).toHaveBeenCalledTimes(2))

    expect(screen.queryByRole('list', { name: 'Suggested coupons' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Have a coupon code?')).toBeInTheDocument() // manual entry still works
    await placeableAndQuiet()
  })
})

describe('stale async results', () => {
  it('ignores a failed estimate for a pincode that is no longer selected', async () => {
    let rejectFirst!: (e: unknown) => void
    api.deliveryEstimate
      .mockImplementationOnce(() => new Promise((_, rej) => (rejectFirst = rej)))
      .mockResolvedValueOnce(estimate({ pincode: '110001', shippingFee: 90 }))
    const user = userEvent.setup()
    renderCheckout()
    await screen.findByRole('radio', { name: /Office/ })

    await user.click(radio(/Office/))
    await waitFor(() => expect(summaryValue('Shipping')).toBe('₹90.00'))
    await act(async () => {
      rejectFirst(problem(500, 'LATE', {}, 'Late failure for the old pincode'))
    })

    expect(screen.queryByText('Late failure for the old pincode')).not.toBeInTheDocument()
    expect(summaryValue('Shipping')).toBe('₹90.00')
    expect(radio(/Cash on Delivery/)).toBeEnabled()
  })

  it('does not throw or log when responses arrive after the page unmounted (no-throw check only)', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const late: Array<() => void> = []
    const later = <T,>(value: T) => new Promise<T>((res) => late.push(() => res(value)))
    api.deliveryEstimate.mockImplementation(() => later(estimate()))
    api.availableCoupons.mockImplementation(() => later([]))
    api.paymentConfig.mockImplementation(() => later({ mode: 'SIMULATED' }))
    api.wallet.mockImplementation(() => later(wallet(5000)))
    api.coins.mockImplementation(() => later(coins(100)))
    api.membership.mockImplementation(() => later({ active: true, plan: null, endsAt: null, benefits: [], plans: [] }))
    const { unmount } = renderCheckout()
    await screen.findByRole('radio', { name: /Home/ })

    unmount()
    await act(async () => {
      for (const resolve of late) resolve()
    })

    expect(errors).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /place order/i })).not.toBeInTheDocument()
  })

})
