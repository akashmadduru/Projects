/**
 * Order API client (order-service via gateway)
 */

import { apiDownload, apiFetch } from '../lib/http'
import type {
  BackendPagedModel,
  DeliveryEstimate,
  Order,
  OrderSummary,
  PlaceOrderRequest,
  ReturnRequest,
  ReturnResponse,
} from '../types'

export class OrderApiClient {
  /** The idempotency key must be reused when retrying the same checkout attempt. */
  place(request: PlaceOrderRequest, idempotencyKey: string): Promise<Order> {
    return apiFetch<Order>('/api/orders', {
      method: 'POST',
      body: request,
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

  invoice(id: string): Promise<Blob> {
    return apiDownload(`/api/orders/${id}/invoice.pdf`)
  }

  deliveryEstimate(pincode: string, subtotal: number, currency: string): Promise<DeliveryEstimate> {
    return apiFetch<DeliveryEstimate>('/api/orders/delivery-estimate', { params: { pincode, subtotal, currency } })
  }

  requestReturn(id: string, request: ReturnRequest): Promise<ReturnResponse> {
    return apiFetch<ReturnResponse>(`/api/orders/${id}/returns`, { method: 'POST', body: request })
  }

  listReturns(id: string): Promise<ReturnResponse[]> {
    return apiFetch<ReturnResponse[]>(`/api/orders/${id}/returns`)
  }
}

export const orderApi = new OrderApiClient()
