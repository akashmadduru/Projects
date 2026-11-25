/**
 * CheckoutPage part 3 (TD-TEST-02): placing the order. Request payloads per payment method,
 * navigation, card payment hand-off, Buy Now items, double-click protection, Idempotency-Key reuse,
 * and the OUT_OF_STOCK / PAYMENT_METHOD_UNAVAILABLE problems.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('../../services/userApi', async () => (await import('./checkoutMocks')).userApiModule())
vi.mock('../../services/orderApi', async () => (await import('./checkoutMocks')).orderApiModule())
vi.mock('../../services/paymentApi', async () => (await import('./checkoutMocks')).paymentApiModule())
vi.mock('../../services/engageApi', async () => (await import('./checkoutMocks')).engageApiModule())
vi.mock('../../services/productApi', async () => (await import('./checkoutMocks')).productApiModule())
vi.mock('../../components/ExchangeOffer', () => ({ ExchangeOffer: () => null }))
vi.mock('../../components/PaymentPanel', async () => {
  const React = await import('react')
  return {
    PaymentPanel: (props: { orderId: string; payment: { mode: string }; onSubmitted: () => void }) =>
      React.createElement(
        'div',
        { 'data-testid': 'payment-panel', 'data-order': props.orderId, 'data-mode': props.payment.mode },
        React.createElement('button', { type: 'button', onClick: props.onSubmitted }, 'Mock payment submitted'),
      ),
  }
})

import { api } from './checkoutMocks'
import { shouldRotateKey } from '../CheckoutPage'
import { errorMessage } from '../../lib/errors'
import { ApiErrorImpl } from '../../lib/problem'
import {
  coins,
  order,
  placeButton,
  problem,
  product,
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

const ORDER_ID = 'abcdef12-3456-7890-abcd-ef1234567890'

beforeEach(() => {
  resetApis()
  seedApis()
})

afterEach(() => {
  cleanup()
  resetApis()
  resetCart()
  vi.restoreAllMocks()
})

const landing = () => screen.findByTestId('landing')
const requestOf = (call = 0) => api.place.mock.calls[call]?.[0]
const keyOf = (call = 0) => api.place.mock.calls[call]?.[1] as string

describe('cart orders', () => {
  let attachServer: ReturnType<typeof seedCart>
  beforeEach(() => {
    attachServer = seedCart([
      { productId: 'p1', name: 'Blue Shirt', price: 500, qty: 2 },
      { productId: 'p2', name: 'Steel Mug', price: 250, qty: 1 },
    ])
  })

  it('places a COD order, replaces history with /orders/<id> and re-attaches the cart', async () => {
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    const where = await landing()
    expect(where).toHaveTextContent(`/orders/${ORDER_ID}`)
    expect(where).toHaveAttribute('data-nav', 'REPLACE')
    expect(where).toHaveAttribute('data-state', '{"placed":true}')
    expect(api.place).toHaveBeenCalledTimes(1)
    expect(requestOf()).toEqual({ addressId: 'a1', paymentMethod: 'COD' })
    expect(keyOf()).toEqual(expect.any(String))
    expect(attachServer).toHaveBeenCalledTimes(1)
  })

  it('sends the selected address id', async () => {
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(radio(/Office/))
    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    await landing()
    expect(requestOf()).toEqual({ addressId: 'a2', paymentMethod: 'COD' })
  })

  it('hands a PENDING_PAYMENT card order to the payment panel without navigating', async () => {
    api.place.mockResolvedValue(
      order({
        status: 'PENDING_PAYMENT',
        paymentMethod: 'CARD',
        totalAmount: 1290.5,
        payment: { paymentId: 'pay1', status: 'REQUIRES_PAYMENT', mode: 'SIMULATED' },
      }),
    )
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(screen.getByRole('button', { name: 'Place order and pay' }))

    expect(await screen.findByRole('heading', { name: 'Complete your payment' })).toBeInTheDocument()
    expect(screen.getByText(/Order #abcdef12/)).toHaveTextContent('₹1,290.50')
    expect(screen.getByTestId('payment-panel')).toHaveAttribute('data-order', ORDER_ID)
    expect(screen.getByRole('link', { name: 'Pay later from the order page' })).toHaveAttribute(
      'href',
      `/orders/${ORDER_ID}`,
    )
    expect(requestOf()).toEqual({ addressId: 'a1', paymentMethod: 'CARD' })
    expect(attachServer).toHaveBeenCalledTimes(1)
    // nothing else on the page is editable and we have not left it
    expect(screen.queryByTestId('landing')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /place order/i })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Mock payment submitted' }))
    const where = await landing()
    expect(where).toHaveTextContent(`/orders/${ORDER_ID}?payment=return`)
    expect(where).toHaveAttribute('data-nav', 'REPLACE')
    expect(where).toHaveAttribute('data-state', '{"placed":true}')
  })

  it('goes straight to the order when a card order has no payment to collect', async () => {
    api.place.mockResolvedValue(order({ status: 'PLACED', paymentMethod: 'CARD', payment: null }))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(screen.getByRole('button', { name: 'Place order and pay' }))

    expect(await landing()).toHaveTextContent(`/orders/${ORDER_ID}`)
    expect(screen.queryByRole('heading', { name: 'Complete your payment' })).not.toBeInTheDocument()
  })

  it('sends paymentMethod WALLET and useWallet when the wallet covers the whole total', async () => {
    api.wallet.mockResolvedValue(wallet(5000))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(await screen.findByRole('radio', { name: /Wallet/ }))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    await landing()
    expect(requestOf()).toEqual({ addressId: 'a1', paymentMethod: 'WALLET', useWallet: true })
  })

  it('sends useWallet for a partial wallet payment on top of the chosen method', async () => {
    api.wallet.mockResolvedValue(wallet(500))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(await screen.findByRole('checkbox', { name: /Use wallet balance/ }))
    await user.click(screen.getByRole('button', { name: 'Place order and pay' }))

    await landing()
    expect(requestOf()).toEqual({ addressId: 'a1', paymentMethod: 'CARD', useWallet: true })
  })

  it('sends the validated coupon code and the redeemed coins', async () => {
    api.validateCoupon.mockResolvedValue({ code: 'SAVE100', description: null, discountAmount: 100, currency: 'INR' })
    api.coins.mockResolvedValue(coins(500))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.type(screen.getByLabelText('Have a coupon code?'), 'save100')
    await user.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(summaryValue('Discount')).toBe('-₹100.00'))
    fireEvent.change(await screen.findByRole('spinbutton', { name: 'Coins to redeem' }), { target: { value: '100' } })
    await waitFor(() => expect(summaryValue(/^Coins/)).toBe('-₹100.00'))
    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    await landing()
    expect(requestOf()).toEqual({
      addressId: 'a1',
      paymentMethod: 'COD',
      couponCode: 'SAVE100',
      coinsToRedeem: 100,
    })
  })
})

describe('Buy Now', () => {
  it('sends an items array and leaves the cart alone', async () => {
    const attachServer = seedCart()
    api.place.mockResolvedValue(order())
    const user = userEvent.setup()
    renderCheckout('/checkout?buyNow=bn1&qty=3')
    await ready()

    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    await landing()
    expect(requestOf()).toEqual({
      addressId: 'a1',
      paymentMethod: 'COD',
      items: [{ productId: 'bn1', quantity: 3 }],
    })
    expect(attachServer).not.toHaveBeenCalled()
    // the cart line is not part of the summary
    expect(api.productDetail).toHaveBeenCalledWith('bn1')
  })

  it('includes variantId and offerId from the URL', async () => {
    api.productDetail.mockResolvedValue(
      product({
        variants: [
          { id: 'v1', sku: 'S1', attributes: { color: 'Red', size: 'M' }, priceAmount: 250, salePrice: 200, imageUrl: null, active: true, availableQuantity: 5 },
        ],
      }),
    )
    const user = userEvent.setup()
    renderCheckout('/checkout?buyNow=bn1&qty=2&variant=v1&offer=o9')
    await ready()

    expect(within(screen.getByRole('complementary')).getByText('Red / M')).toBeInTheDocument()
    expect(summaryValue('Subtotal')).toBe('₹400.00') // variant sale price 200 x 2
    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    await landing()
    expect(requestOf().items).toEqual([{ productId: 'bn1', offerId: 'o9', variantId: 'v1', quantity: 2 }])
  })

  it('falls back to the product price and omits the variant for an unknown variant id', async () => {
    const user = userEvent.setup()
    renderCheckout('/checkout?buyNow=bn1&qty=2&variant=missing')
    await ready()

    expect(summaryValue('Subtotal')).toBe('₹600.00') // product price 300 x 2
    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    await landing()
    expect(requestOf().items).toEqual([{ productId: 'bn1', quantity: 2 }])
  })

  it.each([
    ['0', 1],
    ['abc', 1],
    ['', 1],
    ['-4', 1],
    ['500', 99],
    ['99', 99],
    ['7', 7],
    ['3.9', 3],
  ])('clamps qty=%j to %i', async (qty, expected) => {
    const user = userEvent.setup()
    renderCheckout(`/checkout?buyNow=bn1&qty=${qty}`)
    await ready()

    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    await landing()
    expect(requestOf().items).toEqual([{ productId: 'bn1', quantity: expected }])
  })

  it('defaults the quantity to 1 when qty is missing', async () => {
    const user = userEvent.setup()
    renderCheckout('/checkout?buyNow=bn1')
    await ready()

    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    await landing()
    expect(requestOf().items).toEqual([{ productId: 'bn1', quantity: 1 }])
  })
})

describe('in flight and idempotency', () => {
  beforeEach(() => {
    seedCart()
  })

  it('disables the button while placing and ignores a second click', async () => {
    let resolve!: (o: ReturnType<typeof order>) => void
    api.place.mockImplementation(() => new Promise((res) => (resolve = res)))
    renderCheckout()
    await ready()

    fireEvent.click(placeButton())
    const busy = await screen.findByRole('button', { name: 'Placing order...' })
    expect(busy).toBeDisabled()
    fireEvent.click(busy)
    fireEvent.click(busy)
    expect(api.place).toHaveBeenCalledTimes(1)

    resolve(order())
    expect(await landing()).toHaveTextContent(`/orders/${ORDER_ID}`)
    expect(api.place).toHaveBeenCalledTimes(1)
  })

  it('shows the problem message after a 4xx, re-enables the button and retries with a NEW key', async () => {
    api.place
      .mockRejectedValueOnce(problem(400, 'COUPON_EXPIRED', {}, 'That coupon has expired'))
      .mockResolvedValueOnce(order())
    renderCheckout()
    await ready()

    fireEvent.click(placeButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('That coupon has expired')
    expect(placeButton()).toBeEnabled()
    expect(placeButton()).toHaveTextContent('Place order and pay')

    fireEvent.click(placeButton())
    await landing()
    expect(api.place).toHaveBeenCalledTimes(2)
    expect(keyOf(1)).not.toBe(keyOf(0))
  })

  it('reuses the SAME key when retrying after a 5xx', async () => {
    api.place
      .mockRejectedValueOnce(problem(503, 'UNAVAILABLE', {}, 'Orders are busy'))
      .mockRejectedValueOnce(problem(500, 'BOOM', {}, 'Still busy'))
      .mockResolvedValueOnce(order())
    renderCheckout()
    await ready()

    fireEvent.click(placeButton())
    expect(await screen.findByText('Orders are busy')).toBeInTheDocument()
    fireEvent.click(placeButton())
    expect(await screen.findByText('Still busy')).toBeInTheDocument()
    fireEvent.click(placeButton())
    await landing()

    expect(api.place).toHaveBeenCalledTimes(3)
    expect(keyOf(1)).toBe(keyOf(0))
    expect(keyOf(2)).toBe(keyOf(0))
  })

  it('reuses the SAME key when retrying after a network failure', async () => {
    api.place.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(order())
    renderCheckout()
    await ready()

    fireEvent.click(placeButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the server')
    fireEvent.click(placeButton())
    await landing()

    expect(keyOf(1)).toBe(keyOf(0))
  })

  it('uses a NEW key when the request shape changed after a failure', async () => {
    api.place.mockRejectedValueOnce(problem(503, 'UNAVAILABLE', {}, 'Orders are busy')).mockResolvedValueOnce(order())
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    fireEvent.click(placeButton())
    await screen.findByText('Orders are busy')
    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    await landing()
    expect(requestOf(0).paymentMethod).toBe('CARD')
    expect(requestOf(1).paymentMethod).toBe('COD')
    expect(keyOf(1)).not.toBe(keyOf(0))
  })

  it('a freshly mounted checkout page generates its own key (does not cover "success clears the key")', async () => {
    api.place.mockResolvedValue(order())
    const { unmount } = renderCheckout()
    await ready()
    fireEvent.click(placeButton())
    await landing()
    unmount()

    renderCheckout()
    await ready()
    fireEvent.click(placeButton())
    await waitFor(() => expect(api.place).toHaveBeenCalledTimes(2))
    expect(keyOf(1)).not.toBe(keyOf(0))
  })
})

describe('problems from the order service', () => {
  beforeEach(() => {
    seedCart([
      { productId: 'p1', name: 'Blue Shirt', price: 500, qty: 2 },
      { productId: 'p2', name: 'Steel Mug', price: 250, qty: 1 },
    ])
  })

  it('lists each OUT_OF_STOCK line with the product name, falling back to "Item"', async () => {
    api.place.mockRejectedValue(
      problem(409, 'OUT_OF_STOCK', {
        items: [
          { productId: 'p1', requested: 5, available: 2 },
          { productId: 'unknown', requested: 1, available: 0 },
        ],
      }),
    )
    renderCheckout()
    await ready()

    fireEvent.click(placeButton())

    // the stock list has no accessible name; it is the <ul role=alert> next to the error alert
    const alerts = await screen.findAllByRole('alert')
    const stock = alerts.find((a) => a.tagName === 'UL')!
    const rows = within(stock).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Blue Shirt: you asked for 5, only 2 available.')
    expect(rows[1]).toHaveTextContent('Item: you asked for 1, only 0 available.')
    expect(placeButton()).toBeEnabled()
  })

  it('clears the stock list on the next attempt', async () => {
    api.place
      .mockRejectedValueOnce(problem(409, 'OUT_OF_STOCK', { items: [{ productId: 'p1', requested: 5, available: 2 }] }))
      .mockImplementationOnce(() => new Promise(() => undefined))
    renderCheckout()
    await ready()

    fireEvent.click(placeButton())
    await screen.findByText(/you asked for 5/)
    fireEvent.click(placeButton())

    await screen.findByRole('button', { name: 'Placing order...' })
    expect(screen.queryByText(/you asked for 5/)).not.toBeInTheDocument()
  })

  it('treats OUT_OF_STOCK without an items array as a plain error', async () => {
    api.place.mockRejectedValue(problem(409, 'OUT_OF_STOCK', {}, 'Not enough stock'))
    renderCheckout()
    await ready()

    fireEvent.click(placeButton())

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Some items are out of stock or not available in the quantity you asked for.',
    )
    expect(screen.getAllByRole('alert')).toHaveLength(1) // no per-item stock list
  })

  it('switches to CARD and disables COD with a risk reason on PAYMENT_METHOD_UNAVAILABLE / RISK', async () => {
    api.place.mockRejectedValue(problem(409, 'PAYMENT_METHOD_UNAVAILABLE', { reason: 'RISK' }))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Cash on Delivery is not available for this order. Please choose online payment.',
    )
    expect(radio(/Pay online/).checked).toBe(true)
    expect(radio(/Cash on Delivery/)).toBeDisabled()
    expect(radio(/Cash on Delivery/).checked).toBe(false)
    expect(document.getElementById('cod-reason')).toHaveTextContent(
      'Cash on Delivery is not available for this order. Please pay online.',
    )
    expect(placeButton()).toBeEnabled()
  })

  it('keeps COD after PAYMENT_METHOD_UNAVAILABLE for a reason other than RISK', async () => {
    api.place.mockRejectedValue(problem(409, 'PAYMENT_METHOD_UNAVAILABLE', { reason: 'REGION' }, 'COD not in region'))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That payment method is not available for this order. Please choose another.',
    )
    expect(radio(/Cash on Delivery/)).toBeEnabled()
    expect(radio(/Cash on Delivery/).checked).toBe(true)
  })

  it('dismisses the error alert', async () => {
    api.place.mockRejectedValue(problem(400, 'BAD', {}, 'Something is off'))
    const user = userEvent.setup()
    renderCheckout()
    await ready()

    fireEvent.click(placeButton())
    const alert = await screen.findByRole('alert')
    await user.click(within(alert).getByRole('button'))

    expect(screen.queryByText('Something is off')).not.toBeInTheDocument()
  })
})

const noCode = (status: number) =>
  new ApiErrorImpl(status, { type: 'about:blank', title: 'x', status, detail: 'no code here' })

const KEEP_CASES: Array<[string, () => unknown]> = [
  ['408 (no code)', () => noCode(408)],
  ['408 with a rotate code', () => problem(408, 'VALIDATION_FAILED')],
  ['429 RATE_LIMITED', () => problem(429, 'RATE_LIMITED')],
  ['409 RESOURCE_CONFLICT', () => problem(409, 'RESOURCE_CONFLICT')],
  ['409 unknown code', () => problem(409, 'SOMETHING_NEW')],
  ['409 lower-case code', () => problem(409, 'out_of_stock')],
  ['409 no code', () => noCode(409)],
  ['500', () => problem(500, 'BOOM')],
  ['502', () => problem(502, 'BAD_GATEWAY')],
  ['503 DEPENDENCY_UNAVAILABLE', () => problem(503, 'DEPENDENCY_UNAVAILABLE')],
  ['503 with a rotate code', () => problem(503, 'OUT_OF_STOCK')],
  ['network TypeError', () => new TypeError('Failed to fetch')],
  ['non-error value', () => 'oops'],
]

const ROTATE_409 = [
  'OUT_OF_STOCK',
  'PAYMENT_METHOD_UNAVAILABLE',
  'INSUFFICIENT_BALANCE',
  'CART_EMPTY',
  'PRODUCT_UNAVAILABLE',
  'CURRENCY_MISMATCH',
  'COUPON_USAGE_LIMIT',
]
const ROTATE_422 = [
  'IDEMPOTENCY_KEY_REUSED',
  'PAYMENT_METHOD_UNAVAILABLE',
  'DOMAIN_RULE_VIOLATION',
  'COUPON_INVALID',
  'COUPON_EXPIRED',
  'COUPON_NOT_APPLICABLE',
]

const ROTATE_CASES: Array<[string, () => unknown]> = [
  ['400 VALIDATION_FAILED', () => problem(400, 'VALIDATION_FAILED')],
  ['401', () => problem(401, 'UNAUTHORIZED')],
  ['403', () => problem(403, 'FORBIDDEN')],
  ['404 ADDRESS_NOT_FOUND', () => problem(404, 'ADDRESS_NOT_FOUND')],
  ['405 other 4xx', () => noCode(405)],
  ['413 other 4xx', () => problem(413, 'TOO_BIG')],
  ['409 OUT_OF_STOCK without items', () => problem(409, 'OUT_OF_STOCK')],
  ...ROTATE_409.map((c): [string, () => unknown] => [`409 ${c}`, () => problem(409, c)]),
  ...ROTATE_422.map((c): [string, () => unknown] => [`422 ${c}`, () => problem(422, c)]),
  ['422 unlisted code', () => problem(422, 'SOMETHING_NEW')],
]

describe('shouldRotateKey', () => {
  it.each(KEEP_CASES)('keeps the key on %s', (_n, make) => {
    expect(shouldRotateKey(make())).toBe(false)
  })
  it.each(ROTATE_CASES)('rotates the key on %s', (_n, make) => {
    expect(shouldRotateKey(make())).toBe(true)
  })
})

describe('Idempotency-Key retention by failure class', () => {
  beforeEach(() => {
    seedCart()
  })

  /** Place, fail, check message + button, press again unchanged; returns both keys. */
  async function failThenRetry(make: () => unknown) {
    const err = make()
    api.place.mockRejectedValueOnce(err).mockResolvedValueOnce(order())
    renderCheckout()
    await ready()
    fireEvent.click(placeButton())
    await waitFor(() => expect(screen.getAllByRole('alert')[0]).toHaveTextContent(errorMessage(err)))
    expect(placeButton()).toBeEnabled()
    fireEvent.click(placeButton())
    await landing()
    expect(api.place).toHaveBeenCalledTimes(2)
    return [keyOf(0), keyOf(1)]
  }

  it.each(KEEP_CASES.filter(([n]) => !n.includes('network') && !n.includes('non-error')))(
    'sends the SAME key after %s',
    async (_n, make) => {
      const [first, second] = await failThenRetry(make)
      expect(second).toBe(first)
    },
  )

  it.each(ROTATE_CASES)('sends a NEW key after %s', async (_n, make) => {
    const [first, second] = await failThenRetry(make)
    expect(second).not.toBe(first)
  })

  it('keeps the same key across repeated 429s', async () => {
    api.place
      .mockRejectedValueOnce(problem(429, 'RATE_LIMITED'))
      .mockRejectedValueOnce(problem(429, 'RATE_LIMITED'))
      .mockResolvedValueOnce(order())
    renderCheckout()
    await ready()
    fireEvent.click(placeButton())
    await waitFor(() => expect(api.place).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(placeButton()).toBeEnabled())
    fireEvent.click(placeButton())
    await waitFor(() => expect(api.place).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(placeButton()).toBeEnabled())
    fireEvent.click(placeButton())
    await landing()
    expect(keyOf(1)).toBe(keyOf(0))
    expect(keyOf(2)).toBe(keyOf(0))
  })

  it('uses a NEW key when the payment method changes after a 429', async () => {
    api.place.mockRejectedValueOnce(problem(429, 'RATE_LIMITED')).mockResolvedValueOnce(order())
    const user = userEvent.setup()
    renderCheckout()
    await ready()
    fireEvent.click(placeButton())
    await screen.findByRole('alert')
    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))
    await landing()
    expect(keyOf(1)).not.toBe(keyOf(0))
  })

  it('uses a NEW key after a KEEP failure followed by a definitive rejection', async () => {
    api.place
      .mockRejectedValueOnce(problem(408, 'TIMEOUT'))
      .mockRejectedValueOnce(problem(400, 'VALIDATION_FAILED'))
      .mockResolvedValueOnce(order())
    renderCheckout()
    await ready()
    fireEvent.click(placeButton())
    await waitFor(() => expect(api.place).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(placeButton()).toBeEnabled())
    fireEvent.click(placeButton())
    await waitFor(() => expect(api.place).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(placeButton()).toBeEnabled())
    fireEvent.click(placeButton())
    await landing()
    expect(keyOf(1)).toBe(keyOf(0))
    expect(keyOf(2)).not.toBe(keyOf(1))
  })

  it('a successful place clears the key so a later place sends a new key', async () => {
    api.place.mockRejectedValueOnce(problem(429, 'RATE_LIMITED')).mockResolvedValue(order())
    const first = renderCheckout()
    await ready()
    fireEvent.click(placeButton())
    await screen.findByRole('alert')
    fireEvent.click(placeButton())
    await landing()
    first.unmount()
    renderCheckout()
    await ready()
    fireEvent.click(placeButton())
    await waitFor(() => expect(api.place).toHaveBeenCalledTimes(3))
    expect(keyOf(1)).toBe(keyOf(0))
    expect(keyOf(2)).not.toBe(keyOf(1))
  })

  it('RISK still switches to CARD and rotates the key', async () => {
    api.place
      .mockRejectedValueOnce(problem(409, 'PAYMENT_METHOD_UNAVAILABLE', { reason: 'RISK' }))
      .mockResolvedValueOnce(order())
    const user = userEvent.setup()
    renderCheckout()
    await ready()
    await user.click(radio(/Cash on Delivery/))
    await user.click(screen.getByRole('button', { name: 'Place order' }))
    await screen.findByRole('alert')
    expect(radio(/Pay online/).checked).toBe(true)
    fireEvent.click(placeButton())
    await landing()
    expect(keyOf(1)).not.toBe(keyOf(0))
  })
})
