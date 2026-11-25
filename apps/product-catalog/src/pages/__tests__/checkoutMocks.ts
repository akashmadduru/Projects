/**
 * Test-only: the mocked network surface of CheckoutPage, shared by the CheckoutPage.*.test.tsx files.
 * Kept free of component imports so a `vi.mock` factory can load it without a circular import:
 *
 *   vi.mock('../../services/userApi', async () => (await import('./checkoutMocks')).userApiModule())
 */

import { vi } from 'vitest'

export const api = {
  listAddresses: vi.fn(),
  createAddress: vi.fn(),
  deliveryEstimate: vi.fn(),
  place: vi.fn(),
  validateCoupon: vi.fn(),
  availableCoupons: vi.fn(),
  paymentConfig: vi.fn(),
  wallet: vi.fn(),
  coins: vi.fn(),
  membership: vi.fn(),
  productDetail: vi.fn(),
}

export const userApiModule = () => ({
  userApi: { listAddresses: api.listAddresses, createAddress: api.createAddress },
})
export const orderApiModule = () => ({
  orderApi: { deliveryEstimate: api.deliveryEstimate, place: api.place },
})
export const paymentApiModule = () => ({
  paymentApi: { config: api.paymentConfig },
  promotionApi: { validate: api.validateCoupon, available: api.availableCoupons },
})
export const engageApiModule = () => ({
  engageApi: { wallet: api.wallet, coins: api.coins, membership: api.membership },
})
export const productApiModule = () => ({ productApi: { detail: api.productDetail, bySlug: vi.fn() } })
