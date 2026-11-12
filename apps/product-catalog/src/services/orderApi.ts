/**
 * Order API client (order-service via gateway)
 */

import { apiFetch } from '../lib/http'
import type { BackendPagedModel, Order, OrderSummary } from '../types'

export class OrderApiClient {
  /** The idempotency key must be reused when retrying the same checkout attempt. */
  place(addressId: string, idempotencyKey: string): Promise<Order> {
    return apiFetch<Order>('/api/orders', {
      method: 'POST',
      body: { addressId },
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  }

  list(page = 0, size = 10): Promise<BackendPagedModel<OrderSummary>> {
    return apiFetch<BackendPagedModel<OrderSummary>>('/api/orders', { params: { page, size } })
  }

  get(id: string): Promise<Order> {
    return apiFetch<Order>(`/api/orders/${id}`)
  }

  cancel(id: string): Promise<Order> {
    return apiFetch<Order>(`/api/orders/${id}/cancel`, { method: 'POST' })
  }
}

export const orderApi = new OrderApiClient()
