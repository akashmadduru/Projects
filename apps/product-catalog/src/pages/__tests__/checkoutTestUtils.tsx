/**
 * Test-only helpers for the CheckoutPage.*.test.tsx files: fixtures, default API responses, a
 * router harness and small DOM readers. The API mocks themselves live in ./checkoutMocks.
 */

import type { ReactElement } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigationType } from 'react-router-dom'
import { expect, vi } from 'vitest'
import { CheckoutPage } from '../CheckoutPage'
import { useCartStore } from '../../stores'
import { api } from './checkoutMocks'
import { ApiErrorImpl } from '../../lib/problem'
import { sampleProduct } from '../../test/fixtures'
import type { Address, DeliveryEstimate, Order, Product, Wallet, Coins } from '../../types'

export const STORED_KEY = 'pc.post-login-redirect'

export function address(id: string, over: Partial<Address> = {}): Address {
  return {
    id,
    label: id === 'a1' ? 'Home' : 'Office',
    fullName: 'Ada Lovelace',
    phone: null,
    line1: '1 Analytical St',
    line2: null,
    city: 'Bengaluru',
    state: 'KA',
    postalCode: id === 'a1' ? '560001' : '110001',
    country: 'IN',
    isDefault: id === 'a1',
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
    ...over,
  }
}

export function estimate(over: Partial<DeliveryEstimate> = {}): DeliveryEstimate {
  return {
    pincode: '560001',
    serviceable: true,
    estimatedDays: 3,
    estimatedDeliveryDate: '2026-12-01',
    shippingFee: 40,
    freeShippingThreshold: 999,
    currency: 'INR',
    codAvailable: true,
    ...over,
  }
}

export function wallet(balance: number, currency: string | null = 'INR'): Wallet {
  return { balance, currency, transactions: [] }
}

export function coins(balance: number, currency: string | null = 'INR'): Coins {
  return { balance, value: balance, currency, history: [] }
}

export function order(over: Partial<Order> = {}): Order {
  return {
    id: 'abcdef12-3456-7890-abcd-ef1234567890',
    status: 'PLACED',
    currency: 'INR',
    totalAmount: 1040,
    placedAt: '2026-11-25T00:00:00Z',
    cancelledAt: null,
    items: [],
    paymentMethod: 'COD',
    paymentStatus: 'PENDING',
    ...over,
  } as unknown as Order
}

export const problem = (status: number, code: string, extra: Record<string, unknown> = {}, detail = `${code} detail`) =>
  new ApiErrorImpl(status, { type: 'about:blank', title: code, status, detail, code, ...extra })

export function product(over: Partial<Product> = {}): Product {
  return { ...sampleProduct, id: 'bn1', name: 'Buy Now Lamp', priceAmount: 300, priceCurrency: 'INR', images: [], ...over }
}

/** Default happy-path responses: one cart-able address pair, INR estimate, no extras. */
export function seedApis() {
  api.listAddresses.mockResolvedValue([address('a1'), address('a2')])
  api.createAddress.mockResolvedValue(address('a3'))
  api.deliveryEstimate.mockResolvedValue(estimate())
  api.availableCoupons.mockResolvedValue([])
  api.validateCoupon.mockRejectedValue(problem(404, 'COUPON_INVALID', {}, 'Unknown coupon'))
  api.paymentConfig.mockResolvedValue({ mode: 'SIMULATED' })
  api.wallet.mockResolvedValue(wallet(0))
  api.coins.mockResolvedValue(coins(0))
  api.membership.mockResolvedValue({ active: false, plan: null, endsAt: null, benefits: [], plans: [] })
  api.place.mockResolvedValue(order())
  api.productDetail.mockResolvedValue(product())
}

export function resetApis() {
  for (const fn of Object.values(api)) fn.mockReset()
}

/** One INR cart line: "Blue Shirt", 2 x 500. */
export function seedCart(
  items: { productId: string; name: string; price: number; qty: number; currency?: string; variantId?: string; variantLabel?: string }[] = [
    { productId: 'p1', name: 'Blue Shirt', price: 500, qty: 2 },
  ],
) {
  const attachServer = vi.fn().mockResolvedValue(undefined)
  useCartStore.setState({
    items: items.map((i, n) => ({
      productId: i.productId,
      ...(i.variantId ? { variantId: i.variantId, variantLabel: i.variantLabel } : {}),
      quantity: i.qty,
      addedAt: n,
      snapshot: { name: i.name, priceAmount: i.price, priceCurrency: i.currency ?? 'INR' },
    })),
    saved: [],
    serverBacked: false,
    attachServer,
  })
  return attachServer
}

const initialAttachServer = useCartStore.getState().attachServer

export function resetCart() {
  useCartStore.setState({ items: [], saved: [], serverBacked: false, attachServer: initialAttachServer })
}

function Landing() {
  const loc = useLocation()
  const type = useNavigationType()
  return (
    <div data-testid="landing" data-nav={type} data-state={JSON.stringify(loc.state)}>
      {loc.pathname}
      {loc.search}
    </div>
  )
}

export function renderCheckout(url = '/checkout'): ReturnType<typeof render> {
  const ui: ReactElement = (
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/checkout" element={<CheckoutPage />} />
        <Route path="*" element={<Landing />} />
      </Routes>
    </MemoryRouter>
  )
  return render(ui)
}

/** Waits for the address list, the first-address delivery estimate and the side calls. */
export async function ready() {
  await screen.findByRole('button', { name: /place order/i })
  await waitFor(() => expect(api.deliveryEstimate).toHaveBeenCalled())
  await screen.findByText(/Delivery by/)
}

export const placeButton = () => screen.getByRole('button', { name: /place(ing)? order/i })

/** The <dd> value beside a summary <dt> label. */
export function summaryValue(label: string | RegExp): string {
  const dt = screen.getByText(label, { selector: 'dt' })
  return dt.parentElement?.querySelector('dd')?.textContent ?? ''
}

export const radio = (name: RegExp) => screen.getByRole('radio', { name }) as HTMLInputElement

export function summary() {
  return within(screen.getByRole('complementary'))
}
