import type { PagedModel } from '@/types/api';

/** Phase 9 marketplace types. Shapes follow phase9-contract.md; optional fields are tolerated defensively. */

export type SellerStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';

export interface PickupAddress {
  readonly line1: string;
  readonly line2?: string;
  readonly city: string;
  readonly state: string;
  readonly pincode: string;
}

export interface Seller {
  readonly id: string;
  readonly userId?: string;
  readonly legalName: string;
  readonly displayName: string;
  readonly slug?: string;
  readonly email?: string;
  readonly phone?: string;
  readonly gstin?: string | null;
  readonly pan?: string | null;
  /** KYC is masked: only the last four digits of the bank account ever reach the UI. */
  readonly bankAccountLast4?: string | null;
  readonly ifsc?: string | null;
  readonly pickupAddress?: PickupAddress | null;
  readonly status: SellerStatus;
  readonly rejectionReason?: string | null;
  readonly commissionPercent?: number;
  readonly ratingAvg?: number | null;
  readonly ratingCount?: number;
  readonly createdAt?: string;
  readonly approvedAt?: string | null;
}

export interface SellerApplyRequest {
  readonly legalName: string;
  readonly displayName: string;
  readonly email: string;
  readonly phone: string;
  readonly gstin: string;
  readonly pan: string;
  readonly bankAccountNumber: string;
  readonly ifsc: string;
  readonly pickupAddress: PickupAddress;
  /** false = save as DRAFT, true = submit for review. */
  readonly submit: boolean;
}

export interface Warehouse {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly sellerId?: string | null;
  readonly pincode: string;
  readonly state: string;
  readonly active: boolean;
}

export interface WarehouseRequest {
  readonly code: string;
  readonly name: string;
  readonly pincode: string;
  readonly state: string;
  readonly active: boolean;
}

export interface Offer {
  readonly id: string;
  readonly productId: string;
  readonly variantId?: string | null;
  readonly productName?: string | null;
  readonly sku?: string | null;
  readonly price: number;
  readonly salePrice?: number | null;
  readonly currency: string;
  readonly dispatchDays?: number;
  readonly active: boolean;
  readonly availableQuantity?: number | null;
}

export interface OfferRequest {
  readonly productId?: string;
  readonly variantId?: string;
  readonly price: number;
  readonly salePrice?: number;
  readonly currency: string;
  readonly dispatchDays?: number;
  readonly active?: boolean;
}

export interface SellerProductRequest {
  readonly name: string;
  readonly description?: string;
  readonly categoryId?: string;
  readonly brand?: string;
  readonly sku?: string;
  readonly imageUrl?: string;
  readonly price: number;
  readonly salePrice?: number;
  readonly currency: string;
  readonly dispatchDays?: number;
  readonly quantity?: number;
  readonly warehouseCode?: string;
}

export interface StockRequest {
  readonly offerId: string;
  readonly warehouseCode: string;
  readonly quantity: number;
}

export interface BulkUploadError {
  readonly row: number;
  readonly message: string;
}

export interface BulkUploadResult {
  readonly created: number;
  readonly updated: number;
  readonly errors: readonly BulkUploadError[];
}

export interface SellerShipment {
  readonly id: string;
  readonly orderId?: string;
  readonly orderNumber?: string;
  readonly status: string;
  readonly trackingNumber?: string | null;
  readonly carrier?: string | null;
  readonly estimatedDeliveryDate?: string | null;
  readonly amount?: number;
  readonly currency?: string;
  readonly createdAt?: string;
  readonly shipTo?: string | null;
  readonly items?: readonly { productName?: string; name?: string; quantity: number }[];
}

export interface LedgerEntry {
  readonly id: string;
  readonly type: 'SALE' | 'COMMISSION' | 'FEE' | 'REFUND_REVERSAL' | string;
  readonly amount: number;
  readonly currency: string;
  readonly orderId?: string | null;
  readonly shipmentId?: string | null;
  readonly createdAt: string;
  readonly payoutId?: string | null;
}

export interface Payout {
  readonly id: string;
  readonly sellerId?: string;
  readonly sellerName?: string | null;
  readonly amount: number;
  readonly currency: string;
  readonly status: string;
  readonly reference: string;
  readonly createdAt: string;
}

export interface PayoutRunResult {
  readonly payouts?: number;
  readonly count?: number;
  readonly total?: number;
}

export interface SellerAnalytics {
  readonly revenue: number;
  readonly orders: number;
  readonly unitsSold: number;
  readonly returns: number;
  readonly returnRate: number;
  readonly avgRating?: number | null;
  readonly views?: number;
  readonly conversionRate?: number;
  readonly currency?: string;
  readonly topProducts: readonly { productId: string; name: string; units: number; revenue: number }[];
  readonly daily: readonly { date: string; revenue: number; orders: number }[];
}

export type AdStatus = 'ACTIVE' | 'PAUSED' | 'EXHAUSTED';

export interface AdCampaign {
  readonly id: string;
  readonly productId: string;
  readonly keywords: readonly string[];
  readonly categoryId?: string | null;
  readonly bidCpc: number;
  readonly dailyBudget: number;
  readonly currency: string;
  readonly status: AdStatus;
  readonly spentToday?: number;
  readonly clicks?: number;
  readonly impressions?: number;
  readonly createdAt?: string;
}

export interface AdCampaignRequest {
  readonly productId: string;
  readonly keywords: readonly string[];
  readonly categoryId?: string;
  readonly bidCpc: number;
  readonly dailyBudget: number;
  readonly currency: string;
  readonly status: AdStatus;
}

export interface SellerFeedback {
  readonly id: string;
  readonly orderId?: string;
  readonly rating: number;
  readonly comment?: string | null;
  readonly createdAt: string;
}

export type { PagedModel };
