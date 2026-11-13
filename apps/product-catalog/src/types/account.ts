/**
 * Auth, user, cart, wishlist and order types mirrored from the backend services
 */

export interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  authMethod: 'PASSWORD' | 'GOOGLE' | 'OTP'
}

export interface MfaChallenge {
  mfaRequired: true
  mfaToken: string
}

export type LoginResult = TokenResponse | MfaChallenge

export interface TwoFactorSetup {
  otpauthUrl: string
  secretBase32: string
}

export interface AuthSession {
  familyId: string
  device: string
  ipAddress?: string | null
  authMethod?: string | null
  lastUsedAt: string
  current: boolean
  createdAt?: string
}

export interface AuthUser {
  id: string
  email: string
  emailVerified: boolean
  phone?: string | null
  phoneVerified?: boolean
  displayName: string
  status: string
  roles: string[]
  createdAt: string
  twoFactorEnabled?: boolean
}

export interface LoginRequest {
  email: string
  password: string
}

export interface RegisterRequest {
  email: string
  password: string
  displayName: string
}

export interface UserProfile {
  userId: string
  email: string
  displayName: string | null
  phone: string | null
  createdAt: string
}

export interface UpdateProfileRequest {
  displayName: string
  phone: string | null
}

export interface AddressRequest {
  label: string | null
  fullName: string
  phone: string | null
  line1: string
  line2: string | null
  city: string
  state: string | null
  postalCode: string
  country: string
}

export interface Address extends AddressRequest {
  id: string
  isDefault: boolean
  createdAt: string
  updatedAt: string
}

export interface CartLineRequest {
  productId: string
  quantity: number
  name: string
  unitPrice: number
  currency: string
  imageUrl: string | null
  variantId?: string | null
  variantLabel?: string | null
  /** Marketplace offer; the server defaults to the buy box offer when omitted. */
  offerId?: string | null
}

export interface CartItemResponse extends CartLineRequest {
  lineTotal: number
  addedAt: string
}

export interface SavedItemResponse {
  productId: string
  name: string
  unitPrice: number
  currency: string
  imageUrl: string | null
  variantId?: string | null
  variantLabel?: string | null
  savedAt: string
}

export interface CartResponse {
  items: CartItemResponse[]
  savedItems?: SavedItemResponse[]
  currency: string | null
  itemCount: number
  subtotal: number
}

export interface WishlistLine {
  productId: string
  name: string
  price: number
  currency: string
  imageUrl: string | null
  variantId?: string | null
  variantLabel?: string | null
}

export interface WishlistItemResponse extends WishlistLine {
  addedAt: string
}

export type OrderStatus =
  | 'PENDING_PAYMENT'
  | 'PLACED'
  | 'CONFIRMED'
  | 'PACKED'
  | 'SHIPPED'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'CANCELLED'

export type PaymentMethod = 'CARD' | 'COD' | 'WALLET'

export type PaymentStatus =
  | 'PENDING'
  | 'PAID'
  | 'FAILED'
  | 'REFUNDED'
  | 'PARTIALLY_REFUNDED'
  | 'COD_PENDING'
  | 'COD_COLLECTED'

export interface ShipTo {
  fullName: string
  phone: string | null
  line1: string
  line2: string | null
  city: string
  state: string | null
  postalCode: string
  country: string
}

export interface OrderItem {
  productId: string
  sku: string
  name: string
  unitPrice: number
  quantity: number
  lineTotal: number
  imageUrl: string | null
  variantId?: string | null
  variantLabel?: string | null
  offerId?: string | null
  sellerId?: string | null
  shipmentId?: string | null
}

export interface PaymentInfo {
  paymentId: string
  status: string
  mode: 'STRIPE' | 'SIMULATED'
  clientSecret?: string | null
  publishableKey?: string | null
}

export interface TimelineEntry {
  status: OrderStatus
  note: string | null
  actor: 'USER' | 'ADMIN' | 'SYSTEM' | 'SELLER'
  at: string
}

export interface OrderShipment {
  id: string
  sellerId: string
  sellerName: string
  status: OrderStatus
  trackingNumber: string | null
  carrier: string | null
  estimatedDeliveryDate: string | null
  /** Lines of this shipment (the order items it contains). */
  items: OrderItem[]
  timeline: TimelineEntry[]
}

export interface Order {
  id: string
  status: OrderStatus
  currency: string
  totalAmount: number
  placedAt: string
  cancelledAt: string | null
  shipTo: ShipTo
  items: OrderItem[]
  paymentMethod: PaymentMethod
  paymentStatus: PaymentStatus
  subtotalAmount: number
  discountAmount: number
  couponCode: string | null
  shippingAmount: number
  taxAmount: number
  taxLabel: string | null
  coinsRedeemed?: number | null
  coinsValue?: number | null
  walletAmount?: number | null
  membershipFreeShipping?: boolean | null
  /** Placed but held for a manual risk review before confirmation. */
  riskHold?: boolean | null
  exchangeValue?: number | null
  taxBreakdown: { cgst: number | null; sgst: number | null; igst: number | null } | null
  estimatedDeliveryDate: string | null
  trackingNumber: string | null
  carrier: string | null
  deliveredAt: string | null
  cancellable: boolean
  returnable: boolean
  returnWindowEndsAt: string | null
  timeline: TimelineEntry[]
  payment?: PaymentInfo | null
  shipments?: OrderShipment[] | null
}

export interface OrderSummary {
  id: string
  status: OrderStatus
  currency: string
  totalAmount: number
  placedAt: string
  itemCount: number
  paymentMethod: PaymentMethod
  paymentStatus: PaymentStatus
}

export interface PlaceOrderRequest {
  addressId: string
  paymentMethod: PaymentMethod
  couponCode?: string
  useWallet?: boolean
  coinsToRedeem?: number
  exchangeQuoteId?: string
  items?: { productId: string; offerId?: string; variantId?: string; quantity: number }[]
}

export interface DeliveryEstimate {
  pincode: string
  serviceable: boolean
  estimatedDays: number
  estimatedDeliveryDate: string
  shippingFee: number
  freeShippingThreshold: number
  currency: string
  codAvailable: boolean
}

export interface PaymentConfig {
  mode: 'STRIPE' | 'SIMULATED'
  publishableKey?: string | null
}

export interface CouponValidation {
  code: string
  description: string | null
  discountAmount: number
  currency: string
}

export interface AvailableCoupon {
  code: string
  description: string | null
  discountAmount?: number | null
  minOrderAmount: number | null
}

export type ReturnReason = 'DAMAGED' | 'WRONG_ITEM' | 'NOT_AS_DESCRIBED' | 'NO_LONGER_NEEDED' | 'OTHER'

export interface ReturnRequest {
  items: { productId: string; variantId?: string; quantity: number }[]
  reason: ReturnReason
  comment?: string
  refundTo?: 'SOURCE' | 'WALLET'
}

export interface ReturnResponse {
  id: string
  orderId: string
  status: 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'PICKED_UP' | 'REFUNDED'
  reason: ReturnReason
  comment: string | null
  items: { productId: string; variantId?: string | null; name: string; quantity: number; unitPrice: number }[]
  refundAmount: number | null
  createdAt: string
  updatedAt: string
  adminNote: string | null
}
