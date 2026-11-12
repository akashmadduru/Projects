/**
 * Phase 8 retention and loyalty payloads: wallet, gift cards, coins, membership, referrals,
 * subscriptions, lists, notifications, preferences, push.
 */

import type { PaymentInfo } from './account'

export interface WalletTxn {
  id: string
  type: 'CREDIT' | 'DEBIT'
  amount: number
  reason: 'REFUND' | 'GIFT_CARD' | 'ORDER' | 'REFERRAL' | 'ADJUSTMENT' | 'CASHBACK'
  referenceId: string | null
  createdAt: string
  balanceAfter: number
}

export interface Wallet {
  balance: number
  currency: string | null
  transactions: WalletTxn[]
}

export type GiftCardStatus = 'ACTIVE' | 'REDEEMED' | 'PENDING_PAYMENT' | 'CANCELLED'

export interface GiftCard {
  id: string
  status: GiftCardStatus
  amount?: number
  currency?: string
  recipientEmail?: string
  message?: string | null
  createdAt?: string
  expiresAt?: string | null
  redeemedAt?: string | null
}

export interface GiftCardRequest {
  amount: number
  currency: string
  recipientEmail: string
  message?: string
}

export interface GiftCardPurchase {
  giftCard: GiftCard
  payment: PaymentInfo
}

export interface CoinEntry {
  id?: string
  delta: number
  reason: string
  referenceId?: string | null
  createdAt: string
  expiresAt?: string | null
}

export interface Coins {
  balance: number
  value: number
  currency?: string | null
  history: CoinEntry[]
  expiringSoon?: number | null
}

export interface MembershipPlan {
  code: string
  name: string
  price: number
  currency: string
  days: number
}

export interface Membership {
  active: boolean
  plan: string | null
  endsAt: string | null
  benefits: string[]
  plans: MembershipPlan[]
}

export interface MembershipPurchase {
  membership: { status?: string; plan?: string }
  payment: PaymentInfo
}

export interface Referrals {
  code: string
  link: string
  invited: number
  rewarded: number
  rewards?: number | null
}

export type SubscriptionStatus = 'ACTIVE' | 'PAUSED' | 'CANCELLED'

export interface Subscription {
  id: string
  productId: string
  variantId: string | null
  quantity: number
  frequencyDays: number
  nextRunOn: string
  addressId: string
  paymentMethod: 'COD' | 'WALLET'
  status: SubscriptionStatus
  discountPercent: number
  failures?: number
  productName?: string | null
}

export interface SubscriptionRequest {
  productId: string
  variantId?: string
  quantity: number
  frequencyDays: number
  addressId: string
  paymentMethod: 'COD' | 'WALLET'
}

export type SubscriptionUpdate = Partial<
  Pick<SubscriptionRequest, 'quantity' | 'frequencyDays' | 'addressId' | 'paymentMethod'> & { status: SubscriptionStatus }
>

export interface ListItem {
  productId: string
  variantId: string | null
  note: string | null
  name: string
  price: number
  currency: string
  imageUrl: string | null
  addedAt?: string
}

export interface ProductList {
  id: string
  name: string
  isDefault: boolean
  visibility: 'PRIVATE' | 'SHARED'
  shareToken?: string | null
  items?: ListItem[]
  itemCount?: number
}

export interface ListItemRequest {
  productId: string
  variantId?: string
  note?: string
  name: string
  price: number
  currency: string
  imageUrl?: string
}

export interface SharedList {
  name: string
  ownerFirstName: string
  items: ListItem[]
}

export interface AppNotification {
  id: string
  type: 'ORDER' | 'ALERT' | 'PROMO' | 'ACCOUNT' | 'SYSTEM'
  title: string
  body: string
  link: string | null
  readAt: string | null
  createdAt: string
}

export interface NotificationPage {
  content: AppNotification[]
  page?: unknown
  unreadCount: number
}

export interface Preferences {
  emailOrders: boolean
  emailPromotions: boolean
  pushOrders: boolean
  pushPromotions: boolean
  sms: boolean
}

export interface BuyAgainEntry {
  productId: string
  variantId: string | null
  lastOrderedAt: string
  timesOrdered: number
}
