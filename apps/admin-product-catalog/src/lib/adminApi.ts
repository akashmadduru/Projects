import { apiFetch } from '@/lib/http';
import type { Inventory, Product, ProductVariant, ProductVariantRequest } from '@/types/api';
import type {
  AdminOrderDetail,
  AdminOrderStats,
  AdminOrderSummary,
  AdminPayment,
  AdminPaymentDetail,
  AdminRating,
  AdminUser,
  AdminUserStats,
  Category,
  CategoryForm,
  AdminReturn,
  Campaign,
  CampaignRequest,
  Coupon,
  CouponRequest,
  Deal,
  DealRequest,
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

// ---- Phase 7: dashboard, customers, reviews, payments, categories ------------------------

export function orderStats(): Promise<AdminOrderStats> {
  return apiFetch<AdminOrderStats>('/api/admin/orders/stats');
}

export function listOrdersForUser(
  userId: string,
  page: number,
  size: number,
): Promise<PagedModel<AdminOrderSummary>> {
  return apiFetch<PagedModel<AdminOrderSummary>>('/api/admin/orders', {
    query: { userId, page, size },
  });
}

export function listUsers(query: {
  q?: string;
  status?: string;
  role?: string;
  page: number;
  size: number;
}): Promise<PagedModel<AdminUser>> {
  return apiFetch<PagedModel<AdminUser>>('/api/admin/users', { query });
}

export function getUser(id: string): Promise<AdminUser> {
  return apiFetch<AdminUser>(`/api/admin/users/${encodeURIComponent(id)}`);
}

export function userStats(): Promise<AdminUserStats> {
  return apiFetch<AdminUserStats>('/api/admin/users/stats');
}

export function setUserStatus(id: string, status: 'ACTIVE' | 'DISABLED'): Promise<AdminUser> {
  return apiFetch<AdminUser>(`/api/admin/users/${encodeURIComponent(id)}/status`, {
    method: 'PUT',
    body: { status },
  });
}

export function setUserRoles(id: string, roles: readonly string[]): Promise<AdminUser> {
  return apiFetch<AdminUser>(`/api/admin/users/${encodeURIComponent(id)}/roles`, {
    method: 'PUT',
    body: { roles },
  });
}

export function listRatings(query: {
  productId?: string;
  rating?: number;
  q?: string;
  page: number;
  size: number;
}): Promise<PagedModel<AdminRating>> {
  return apiFetch<PagedModel<AdminRating>>('/api/admin/ratings', { query });
}

/** Admin delete reuses the customer endpoint; rating-service allows ADMIN and recomputes averages. */
export function deleteRating(id: string): Promise<void> {
  return apiFetch<void>(`/api/ratings/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function listPayments(query: {
  status?: string;
  method?: string;
  orderId?: string;
  page: number;
  size: number;
}): Promise<PagedModel<AdminPayment>> {
  return apiFetch<PagedModel<AdminPayment>>('/api/admin/payments', { query });
}

export function getPayment(id: string): Promise<AdminPaymentDetail> {
  return apiFetch<AdminPaymentDetail>(`/api/admin/payments/${encodeURIComponent(id)}`);
}

export function paymentStats(): Promise<Readonly<Record<string, number>>> {
  return apiFetch<Readonly<Record<string, number>>>('/api/admin/payments/stats');
}

export function listCategories(): Promise<PagedModel<Category>> {
  return apiFetch<PagedModel<Category>>('/api/categories', {
    query: { page: 0, size: 200, sort: 'name,asc' },
  });
}

export function isCategoryActive(c: Category): boolean {
  return c.active ?? c.isActive ?? true;
}

/** Create binds `isActive` (boxed Boolean); both spellings are sent so either binding works. */
export function createCategory(form: CategoryForm): Promise<Category> {
  return apiFetch<Category>('/api/categories', {
    method: 'POST',
    body: {
      name: form.name,
      description: form.description || null,
      parentCategoryId: form.parentCategoryId || null,
      isActive: form.active,
      active: form.active,
    },
  });
}

/** Update binds `active` (primitive boolean) plus the optimistic-lock `version`. Parent is immutable. */
export function updateCategory(id: string, form: CategoryForm, version: number): Promise<Category> {
  return apiFetch<Category>(`/api/categories/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: {
      name: form.name,
      description: form.description || null,
      active: form.active,
      isActive: form.active,
      version,
    },
  });
}

export function deleteCategory(id: string): Promise<void> {
  return apiFetch<void>(`/api/categories/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/** Lowest-stock rows first; the caller filters by each row's own threshold. */
export function lowestStock(size: number): Promise<PagedModel<Inventory>> {
  return apiFetch<PagedModel<Inventory>>('/api/inventory', {
    query: { page: 0, size, sort: 'quantity,asc' },
  });
}

/** Shared pill colour for the many status enums the console shows. */
export function statusBadge(s: string): string {
  if (['DELIVERED', 'SUCCEEDED', 'REFUNDED', 'ACTIVE'].includes(s)) return 'badge-success';
  if (['CANCELLED', 'FAILED', 'REJECTED', 'DISABLED'].includes(s)) return 'badge-error';
  if (['PENDING_PAYMENT', 'REQUESTED', 'COD_PENDING', 'LOCKED', 'PARTIALLY_REFUNDED'].includes(s))
    return 'badge-warning';
  return 'badge-info';
}

// ---- Phase 7: variants + deals ---------------------------------------------------------------

export function getProduct(id: string): Promise<Product> {
  return apiFetch<Product>(`/api/products/${encodeURIComponent(id)}`);
}

/** Full replace of a product's variants. */
export function replaceVariants(
  productId: string,
  variants: readonly ProductVariantRequest[],
): Promise<readonly ProductVariant[]> {
  return apiFetch<readonly ProductVariant[]>(`/api/products/${encodeURIComponent(productId)}/variants`, {
    method: 'PUT',
    body: variants,
  });
}

/** Contract does not pin the list shape; accept a bare array or a PagedModel. */
export async function listDeals(): Promise<readonly Deal[]> {
  const res = await apiFetch<readonly Deal[] | PagedModel<Deal>>('/api/admin/deals', {
    query: { page: 0, size: 100 },
  });
  return Array.isArray(res) ? (res as readonly Deal[]) : (res as PagedModel<Deal>).content;
}

export function createDeal(body: DealRequest): Promise<Deal> {
  return apiFetch<Deal>('/api/admin/deals', { method: 'POST', body });
}

export function updateDeal(id: string, body: DealRequest): Promise<Deal> {
  return apiFetch<Deal>(`/api/admin/deals/${encodeURIComponent(id)}`, { method: 'PUT', body });
}

export function deactivateDeal(id: string): Promise<Deal> {
  return apiFetch<Deal>(`/api/admin/deals/${encodeURIComponent(id)}/deactivate`, { method: 'POST' });
}

// ---- Phase 8: campaigns (notification-service) ----------------------------------------------

/** Contract does not pin the list shape; accept a bare array or a PagedModel. */
export async function listCampaigns(): Promise<readonly Campaign[]> {
  const res = await apiFetch<readonly Campaign[] | PagedModel<Campaign>>('/api/admin/campaigns', {
    query: { page: 0, size: 100 },
  });
  return Array.isArray(res) ? (res as readonly Campaign[]) : (res as PagedModel<Campaign>).content;
}

export function createCampaign(body: CampaignRequest): Promise<Campaign> {
  return apiFetch<Campaign>('/api/admin/campaigns', { method: 'POST', body });
}

/** Send is async server-side; the response may be the campaign or empty. */
export function sendCampaign(id: string): Promise<Campaign | undefined> {
  return apiFetch<Campaign | undefined>(`/api/admin/campaigns/${encodeURIComponent(id)}/send`, {
    method: 'POST',
  });
}
