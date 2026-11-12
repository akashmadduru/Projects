/**
 * Payment and coupon API clients (payment-service and promotion-service via gateway)
 */

import { apiFetch } from '../lib/http'
import type { AvailableCoupon, CouponValidation, PaymentConfig, PaymentInfo } from '../types'

export class PaymentApiClient {
  config(): Promise<PaymentConfig> {
    return apiFetch<PaymentConfig>('/api/payments/config')
  }

  forOrder(orderId: string): Promise<PaymentInfo> {
    return apiFetch<PaymentInfo>(`/api/payments/orders/${orderId}`)
  }

  /** SIMULATED mode only; the server answers 404 otherwise. */
  simulate(paymentId: string, outcome: 'SUCCEEDED' | 'FAILED'): Promise<void> {
    return apiFetch<void>(`/api/payments/${paymentId}/simulate`, { method: 'POST', body: { outcome } })
  }
}

export class PromotionApiClient {
  validate(code: string, subtotal: number, currency: string): Promise<CouponValidation> {
    return apiFetch<CouponValidation>('/api/promotions/coupons/validate', {
      method: 'POST',
      body: { code, subtotal, currency },
    })
  }

  available(subtotal: number, currency: string): Promise<AvailableCoupon[]> {
    return apiFetch<AvailableCoupon[]>('/api/promotions/coupons/available', { params: { subtotal, currency } })
  }
}

export const paymentApi = new PaymentApiClient()
export const promotionApi = new PromotionApiClient()
