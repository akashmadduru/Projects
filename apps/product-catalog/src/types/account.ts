/**
 * Auth, user, cart, wishlist and order types mirrored from the backend services
 */

export interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  authMethod: 'PASSWORD' | 'GOOGLE'
}

export interface AuthUser {
  id: string
  email: string
  emailVerified: boolean
  displayName: string
  status: string
  roles: string[]
  createdAt: string
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
}

export interface CartItemResponse extends CartLineRequest {
  lineTotal: number
  addedAt: string
}

export interface CartResponse {
  items: CartItemResponse[]
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
}

export interface WishlistItemResponse extends WishlistLine {
  addedAt: string
}

export type OrderStatus = 'PLACED' | 'CANCELLED'

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
}

export interface OrderSummary {
  id: string
  status: OrderStatus
  currency: string
  totalAmount: number
  placedAt: string
  itemCount: number
}
