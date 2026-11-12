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
