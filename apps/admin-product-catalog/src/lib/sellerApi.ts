import { apiFetch } from '@/lib/http';
import type {
  AdCampaign,
  AdCampaignRequest,
  BulkUploadResult,
  LedgerEntry,
  Offer,
  OfferRequest,
  OfferView,
  PagedModel,
  Payout,
  PayoutRunResult,
  Seller,
  SellerAnalytics,
  SellerApplyRequest,
  SellerFeedback,
  SellerProductRequest,
  SellerShipment,
  StockRequest,
  Warehouse,
  WarehouseRequest,
} from '@/types/seller';

/** Phase 9: seller-service / catalog / order endpoints used by the seller portal and admin screens. */

/** Contract does not always pin list shapes; accept a bare array or a PagedModel. */
function unwrap<T>(res: readonly T[] | PagedModel<T>): readonly T[] {
  return Array.isArray(res) ? (res as readonly T[]) : (res as PagedModel<T>).content;
}

// ---- onboarding ---------------------------------------------------------------------------

/** Resolves null when the user has never applied (404). */
export async function getMySeller(): Promise<Seller | null> {
  try {
    return await apiFetch<Seller>('/api/sellers/me');
  } catch (e: unknown) {
    if ((e as { status?: number } | null)?.status === 404) return null;
    throw e;
  }
}

export function applySeller(body: SellerApplyRequest): Promise<Seller> {
  return apiFetch<Seller>('/api/sellers/apply', { method: 'POST', body });
}

// ---- offers / products / stock -----------------------------------------------------------

/** Real response: {offer:{offerId,price,...,availableQuantity}, productId, productName, productSku}. */
function flattenOffer(v: OfferView): Offer {
  return {
    id: v.offer.offerId,
    productId: v.productId,
    variantId: v.offer.variantId,
    productName: v.productName,
    sku: v.productSku,
    price: v.offer.price,
    salePrice: v.offer.salePrice,
    currency: v.offer.currency,
    dispatchDays: v.offer.dispatchDays,
    active: v.offer.active,
    availableQuantity: v.offer.availableQuantity,
  };
}

export async function listOffers(): Promise<readonly Offer[]> {
  return unwrap(await apiFetch<readonly OfferView[] | PagedModel<OfferView>>('/api/seller/offers')).map(flattenOffer);
}

export async function createOffer(body: OfferRequest): Promise<Offer> {
  return flattenOffer(await apiFetch<OfferView>('/api/seller/offers', { method: 'POST', body }));
}

export async function updateOffer(id: string, body: OfferRequest): Promise<Offer> {
  return flattenOffer(await apiFetch<OfferView>(`/api/seller/offers/${id}`, { method: 'PUT', body }));
}

export function createSellerProduct(body: SellerProductRequest): Promise<unknown> {
  return apiFetch<unknown>('/api/seller/products', { method: 'POST', body });
}

export function setStock(body: StockRequest): Promise<void> {
  return apiFetch<void>('/api/seller/stock', { method: 'PUT', body });
}

export async function listSellerWarehouses(): Promise<readonly Warehouse[]> {
  return unwrap(await apiFetch<readonly Warehouse[] | PagedModel<Warehouse>>('/api/seller/warehouses'));
}

export function bulkUpload(file: File): Promise<BulkUploadResult> {
  const form = new FormData();
  form.append('file', file);
  return apiFetch<BulkUploadResult>('/api/seller/products/bulk', { method: 'POST', body: form });
}

export const BULK_CSV_HEADER =
  'sku,name,description,categoryId,brand,price,salePrice,currency,quantity,warehouseCode,imageUrl,attributes';

// ---- shipments ---------------------------------------------------------------------------

export async function listSellerShipments(status?: string): Promise<readonly SellerShipment[]> {
  return unwrap(
    await apiFetch<readonly SellerShipment[] | PagedModel<SellerShipment>>('/api/seller/shipments', {
      query: { status, page: 0, size: 100 },
    }),
  );
}

export function setShipmentStatus(
  id: string,
  body: { status: string; trackingNumber?: string; carrier?: string },
): Promise<SellerShipment> {
  return apiFetch<SellerShipment>(`/api/seller/shipments/${id}/status`, { method: 'POST', body });
}

// ---- ledger / payouts / analytics --------------------------------------------------------

export function listLedger(page: number): Promise<PagedModel<LedgerEntry>> {
  return apiFetch<PagedModel<LedgerEntry>>('/api/seller/ledger', { query: { page, size: 25 } });
}

export async function listMyPayouts(): Promise<readonly Payout[]> {
  return unwrap(await apiFetch<readonly Payout[] | PagedModel<Payout>>('/api/seller/payouts'));
}

export function getAnalytics(from?: string, to?: string): Promise<SellerAnalytics> {
  return apiFetch<SellerAnalytics>('/api/seller/analytics', { query: { from, to } });
}

// ---- ads ---------------------------------------------------------------------------------

export async function listAds(): Promise<readonly AdCampaign[]> {
  return unwrap(await apiFetch<readonly AdCampaign[] | PagedModel<AdCampaign>>('/api/seller/ads'));
}

export function createAd(body: AdCampaignRequest): Promise<AdCampaign> {
  return apiFetch<AdCampaign>('/api/seller/ads', { method: 'POST', body });
}

export function updateAd(id: string, body: AdCampaignRequest): Promise<AdCampaign> {
  return apiFetch<AdCampaign>(`/api/seller/ads/${id}`, { method: 'PUT', body });
}

// ---- feedback ----------------------------------------------------------------------------

export function listFeedback(sellerId: string, page: number): Promise<PagedModel<SellerFeedback>> {
  return apiFetch<PagedModel<SellerFeedback>>(`/api/sellers/${sellerId}/feedback`, {
    query: { page, size: 20 },
    authenticated: false,
  });
}

// ---- admin -------------------------------------------------------------------------------

export async function adminListSellers(status?: string): Promise<readonly Seller[]> {
  return unwrap(
    await apiFetch<readonly Seller[] | PagedModel<Seller>>('/api/admin/sellers', {
      query: { status, page: 0, size: 100 },
    }),
  );
}

export function adminDecideSeller(id: string, approve: boolean, reason?: string): Promise<Seller> {
  return apiFetch<Seller>(`/api/admin/sellers/${id}/decision`, {
    method: 'POST',
    body: { approve, reason },
  });
}

export function adminSuspendSeller(id: string): Promise<Seller> {
  return apiFetch<Seller>(`/api/admin/sellers/${id}/suspend`, { method: 'POST' });
}

export function adminRunPayouts(minAgeDays?: number): Promise<PayoutRunResult> {
  return apiFetch<PayoutRunResult>('/api/admin/payouts/run', {
    method: 'POST',
    body: minAgeDays === undefined ? {} : { minAgeDays },
  });
}

export async function adminListPayouts(): Promise<readonly Payout[]> {
  return unwrap(
    await apiFetch<readonly Payout[] | PagedModel<Payout>>('/api/admin/payouts', {
      query: { page: 0, size: 100 },
    }),
  );
}

export async function adminListWarehouses(): Promise<readonly Warehouse[]> {
  return unwrap(await apiFetch<readonly Warehouse[] | PagedModel<Warehouse>>('/api/admin/warehouses'));
}

export function adminCreateWarehouse(body: WarehouseRequest): Promise<Warehouse> {
  return apiFetch<Warehouse>('/api/admin/warehouses', { method: 'POST', body });
}

export function adminUpdateWarehouse(id: string, body: WarehouseRequest): Promise<Warehouse> {
  return apiFetch<Warehouse>(`/api/admin/warehouses/${id}`, { method: 'PUT', body });
}
