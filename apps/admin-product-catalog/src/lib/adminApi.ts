import { apiFetch } from '@/lib/http';
import type {
  AdminOrderDetail,
  AdminOrderSummary,
  AdminReturn,
  Coupon,
  CouponRequest,
  OrderStatusRequest,
  PagedModel,
} from '@/types/commerce';

/** Typed wrappers over the order-service / promotion-service admin endpoints (Phase 6). */

export function listOrders(query: {
  status?: string;
  q?: string;
  page: number;
  size: number;
}): Promise<PagedModel<AdminOrderSummary>> {
  return apiFetch<PagedModel<AdminOrderSummary>>('/api/admin/orders', { query });
}

export function getOrder(id: string): Promise<AdminOrderDetail> {
  return apiFetch<AdminOrderDetail>(`/api/admin/orders/${id}`);
}

export function changeOrderStatus(id: string, body: OrderStatusRequest): Promise<AdminOrderDetail> {
  return apiFetch<AdminOrderDetail>(`/api/admin/orders/${id}/status`, { method: 'POST', body });
}

export function cancelOrder(id: string, reason: string): Promise<AdminOrderDetail> {
  return apiFetch<AdminOrderDetail>(`/api/admin/orders/${id}/cancel`, {
    method: 'POST',
    body: { reason },
  });
}

export function listReturns(query: {
  status?: string;
  page: number;
  size: number;
}): Promise<PagedModel<AdminReturn>> {
  return apiFetch<PagedModel<AdminReturn>>('/api/admin/returns', { query });
}

export function decideReturn(id: string, approve: boolean, note?: string): Promise<AdminReturn> {
  return apiFetch<AdminReturn>(`/api/admin/returns/${id}/decision`, {
    method: 'POST',
    body: { approve, note },
  });
}

export function setReturnStatus(
  id: string,
  status: 'PICKED_UP' | 'REFUNDED',
): Promise<AdminReturn> {
  return apiFetch<AdminReturn>(`/api/admin/returns/${id}/status`, {
    method: 'POST',
    body: { status },
  });
}

/** The contract does not pin the list shape; accept a bare array or a PagedModel. */
export async function listCoupons(): Promise<readonly Coupon[]> {
  const res = await apiFetch<readonly Coupon[] | PagedModel<Coupon>>('/api/admin/coupons', {
    query: { page: 0, size: 100 },
  });
  return Array.isArray(res) ? (res as readonly Coupon[]) : (res as PagedModel<Coupon>).content;
}

export function createCoupon(body: CouponRequest): Promise<Coupon> {
  return apiFetch<Coupon>('/api/admin/coupons', { method: 'POST', body });
}

export function updateCoupon(id: string, body: CouponRequest): Promise<Coupon> {
  return apiFetch<Coupon>(`/api/admin/coupons/${id}`, { method: 'PUT', body });
}

export function deactivateCoupon(id: string): Promise<Coupon> {
  return apiFetch<Coupon>(`/api/admin/coupons/${id}/deactivate`, { method: 'POST' });
}

export function money(amount: number | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) {
    return '-';
  }
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount);
  } catch {
    return `${amount.toFixed(2)} ${currency}`;
  }
}

export function fmtDate(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : '-';
}

export function errorInfo(error: unknown): { message: string; correlationId: string | null } {
  const e = error as { message?: string; correlationId?: string | null } | null;
  return { message: e?.message ?? 'The request failed.', correlationId: e?.correlationId ?? null };
}
