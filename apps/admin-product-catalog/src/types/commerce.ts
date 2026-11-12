import type { PagedModel } from '@/types/api';

export type OrderStatus =
  | 'PENDING_PAYMENT'
  | 'PLACED'
  | 'CONFIRMED'
  | 'PACKED'
  | 'SHIPPED'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'CANCELLED';

export const ORDER_STATUSES: readonly OrderStatus[] = [
  'PENDING_PAYMENT',
  'PLACED',
  'CONFIRMED',
  'PACKED',
  'SHIPPED',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'CANCELLED',
];

export interface AdminOrderSummary {
  readonly id: string;
  readonly status: OrderStatus;
  readonly paymentMethod: string;
  readonly paymentStatus: string;
  readonly email: string | null;
  readonly totalAmount: number;
  readonly currency: string;
  readonly itemCount: number;
  readonly placedAt: string;
}

export interface OrderLine {
  readonly productId: string;
  readonly name: string;
  readonly quantity: number;
  readonly unitPrice: number;
}

export interface OrderAddress {
  readonly fullName?: string;
  readonly line1?: string;
  readonly line2?: string | null;
  readonly city?: string;
  readonly state?: string;
  readonly postalCode?: string;
  readonly country?: string;
  readonly phone?: string | null;
}

export interface TimelineEntry {
  readonly status: string;
  readonly note: string | null;
  readonly actor: string;
  readonly at: string;
}

export interface AdminOrderDetail {
  readonly id: string;
  readonly userId?: string;
  readonly email?: string | null;
  readonly status: OrderStatus;
  readonly paymentMethod: string;
  readonly paymentStatus: string;
  readonly currency: string;
  readonly totalAmount: number;
  readonly subtotalAmount?: number;
  readonly discountAmount?: number;
  readonly couponCode?: string | null;
  readonly shippingAmount?: number;
  readonly taxAmount?: number;
  readonly taxLabel?: string | null;
  readonly trackingNumber?: string | null;
  readonly carrier?: string | null;
  readonly estimatedDeliveryDate?: string | null;
  readonly deliveredAt?: string | null;
  readonly items?: readonly OrderLine[];
  readonly shipTo?: OrderAddress | null;
  readonly timeline?: readonly TimelineEntry[];
}

export interface OrderStatusRequest {
  readonly status: OrderStatus;
  readonly note?: string;
  readonly trackingNumber?: string;
  readonly carrier?: string;
}

export type ReturnStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'PICKED_UP' | 'REFUNDED';

export const RETURN_STATUSES: readonly ReturnStatus[] = [
  'REQUESTED',
  'APPROVED',
  'REJECTED',
  'PICKED_UP',
  'REFUNDED',
];

export interface AdminReturn {
  readonly id: string;
  readonly orderId: string;
  readonly status: ReturnStatus;
  readonly reason: string;
  readonly comment: string | null;
  readonly items: readonly OrderLine[];
  readonly refundAmount: number | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly adminNote: string | null;
}

export type CouponType = 'PERCENT' | 'FLAT';

export interface Coupon {
  readonly id: string;
  readonly code: string;
  readonly description: string | null;
  readonly type: CouponType;
  readonly value: number;
  readonly maxDiscount: number | null;
  readonly minOrderAmount: number | null;
  readonly currency: string | null;
  readonly startsAt: string | null;
  readonly endsAt: string | null;
  readonly totalLimit: number | null;
  readonly perUserLimit: number | null;
  readonly firstOrderOnly: boolean;
  readonly active: boolean;
}

export interface CouponRequest {
  readonly code: string;
  readonly description?: string;
  readonly type: CouponType;
  readonly value: number;
  readonly maxDiscount?: number;
  readonly minOrderAmount?: number;
  readonly currency?: string;
  readonly startsAt?: string;
  readonly endsAt?: string;
  readonly totalLimit?: number;
  readonly perUserLimit?: number;
  readonly firstOrderOnly: boolean;
}

export type { PagedModel };

// ---- Phase 7: admin console completion ---------------------------------------------------

export interface CurrencyRevenue {
  readonly currency: string;
  readonly amount: number;
  readonly orderCount: number;
}

/** `GET /api/admin/orders/stats`. Revenue is net of refunds, excludes PENDING_PAYMENT/CANCELLED. */
export interface AdminOrderStats {
  readonly generatedAt: string;
  readonly ordersByStatus: Readonly<Record<string, number>>;
  readonly returnsByStatus: Readonly<Record<string, number>>;
  readonly revenueToday: readonly CurrencyRevenue[];
  readonly revenueLast30Days: readonly CurrencyRevenue[];
}

export type UserStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED';
export const USER_STATUSES: readonly UserStatus[] = ['ACTIVE', 'DISABLED', 'LOCKED'];

export type AssignableRole = 'ADMIN' | 'CATALOG_MANAGER' | 'VIEWER';
export const ASSIGNABLE_ROLES: readonly AssignableRole[] = ['ADMIN', 'CATALOG_MANAGER', 'VIEWER'];

/** `GET /api/admin/users[/{id}]` (auth-service). Roles are unprefixed. */
export interface AdminUser {
  readonly id: string;
  readonly email: string;
  readonly displayName: string;
  readonly status: UserStatus;
  readonly roles: readonly string[];
  readonly emailVerified: boolean;
  readonly phone: string | null;
  readonly phoneVerified: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AdminUserStats {
  readonly total: number;
  readonly byStatus: Readonly<Record<string, number>>;
}

/** Public review shape from rating-service (no author id by design). */
export interface AdminRating {
  readonly id: string;
  readonly productId: string;
  readonly userDisplayName: string;
  readonly ratingValue: number;
  readonly reviewTitle: string | null;
  readonly reviewText: string | null;
  readonly isVerifiedPurchase: boolean | null;
  readonly helpfulCount: number;
  readonly unhelpfulCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export const PAYMENT_STATUSES: readonly string[] = [
  'REQUIRES_PAYMENT',
  'PROCESSING',
  'SUCCEEDED',
  'FAILED',
  'REFUNDED',
  'PARTIALLY_REFUNDED',
  'COD_PENDING',
];

export interface AdminPayment {
  readonly id: string;
  readonly orderId: string;
  readonly userId: string;
  readonly method: 'CARD' | 'COD';
  readonly mode: 'STRIPE' | 'SIMULATED';
  readonly amount: number;
  readonly currency: string;
  readonly status: string;
  readonly refundedAmount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AdminRefund {
  readonly id: string;
  readonly amount: number;
  readonly reason: string | null;
  readonly createdAt: string;
}

export interface AdminPaymentDetail {
  readonly payment: AdminPayment;
  readonly refunds: readonly AdminRefund[];
}

/**
 * product-service category. Lombok + Jackson serialise `boolean isActive` as `active` on the
 * response; both spellings are read defensively.
 */
export interface Category {
  readonly id: string;
  readonly name: string;
  readonly description: string | null;
  readonly parentCategoryId: string | null;
  readonly depthLevel: number;
  readonly active?: boolean;
  readonly isActive?: boolean;
  readonly version: number;
}

export interface CategoryForm {
  readonly name: string;
  readonly description?: string;
  readonly parentCategoryId?: string | null;
  readonly active: boolean;
}

// ---- Phase 7: deals ------------------------------------------------------------------------

/** promotion-service `GET /api/admin/deals`. */
export interface Deal {
  readonly id: string;
  readonly productId: string;
  readonly variantId: string | null;
  readonly title: string | null;
  readonly dealPrice: number;
  readonly currency: string;
  readonly startsAt: string;
  readonly endsAt: string;
  readonly quantityLimit: number | null;
  readonly claimed: number;
  readonly remaining?: number | null;
  readonly active: boolean;
}

export interface DealRequest {
  readonly productId: string;
  readonly variantId?: string | null;
  readonly title?: string;
  readonly dealPrice: number;
  readonly currency: string;
  readonly startsAt: string;
  readonly endsAt: string;
  readonly quantityLimit?: number;
  readonly active: boolean;
}

// ---- Phase 8: campaigns ----------------------------------------------------------------------

export type CampaignChannel = 'EMAIL' | 'PUSH' | 'INBOX' | 'ALL';
export type CampaignAudience = 'ALL' | 'MEMBERS' | 'INACTIVE_30D';
export type CampaignStatus = 'DRAFT' | 'SENDING' | 'SENT';

export interface Campaign {
  readonly id: string;
  readonly name: string;
  readonly channel: CampaignChannel;
  readonly audience: CampaignAudience;
  readonly subject: string;
  readonly body: string;
  readonly link: string | null;
  readonly status: CampaignStatus;
  readonly sentCount: number;
  readonly createdAt: string;
  readonly sentAt: string | null;
}

export interface CampaignRequest {
  readonly name: string;
  readonly channel: CampaignChannel;
  readonly audience: CampaignAudience;
  readonly subject: string;
  readonly body: string;
  readonly link?: string;
}
