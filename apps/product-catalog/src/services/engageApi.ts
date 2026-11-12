/**
 * Phase 8 retention and loyalty clients: wallet, gift cards, coins, membership, referrals,
 * subscriptions, lists, notifications, preferences, push, buy again.
 */

import { apiFetch } from '../lib/http'
import type {
  AppNotification,
  BuyAgainEntry,
  Coins,
  GiftCard,
  GiftCardPurchase,
  GiftCardRequest,
  ListItemRequest,
  Membership,
  MembershipPurchase,
  NotificationPage,
  Preferences,
  ProductList,
  Referrals,
  SharedList,
  Subscription,
  SubscriptionRequest,
  SubscriptionUpdate,
  Wallet,
} from '../types'

export const engageApi = {
  wallet: () => apiFetch<Wallet>('/api/wallet'),
  giftCards: () => apiFetch<GiftCard[]>('/api/gift-cards'),
  buyGiftCard: (body: GiftCardRequest) => apiFetch<GiftCardPurchase>('/api/gift-cards', { method: 'POST', body }),
  redeemGiftCard: (code: string) => apiFetch<unknown>('/api/gift-cards/redeem', { method: 'POST', body: { code } }),

  coins: (currency?: string) => apiFetch<Coins>('/api/coins', currency ? { params: { currency } } : undefined),

  membership: () => apiFetch<Membership>('/api/membership'),
  joinMembership: (plan: string, currency: string) =>
    apiFetch<MembershipPurchase>('/api/membership', { method: 'POST', body: { plan, currency } }),
  cancelMembership: () => apiFetch<unknown>('/api/membership/cancel', { method: 'POST' }),

  referrals: () => apiFetch<Referrals>('/api/referrals'),
  claimReferral: (code: string) => apiFetch<unknown>('/api/referrals/claim', { method: 'POST', body: { code } }),

  subscriptions: () => apiFetch<Subscription[]>('/api/subscriptions'),
  createSubscription: (body: SubscriptionRequest) =>
    apiFetch<Subscription>('/api/subscriptions', { method: 'POST', body }),
  updateSubscription: (id: string, body: SubscriptionUpdate) =>
    apiFetch<Subscription>(`/api/subscriptions/${id}`, { method: 'PUT', body }),
  skipSubscription: (id: string) => apiFetch<Subscription>(`/api/subscriptions/${id}/skip`, { method: 'POST' }),

  buyAgain: (limit = 20) => apiFetch<BuyAgainEntry[]>('/api/orders/buy-again', { params: { limit } }),

  lists: () => apiFetch<ProductList[]>('/api/lists'),
  createList: (name: string) => apiFetch<ProductList>('/api/lists', { method: 'POST', body: { name } }),
  renameList: (id: string, name: string) => apiFetch<ProductList>(`/api/lists/${id}`, { method: 'PUT', body: { name } }),
  deleteList: (id: string) => apiFetch<void>(`/api/lists/${id}`, { method: 'DELETE' }),
  addListItem: (id: string, body: ListItemRequest) =>
    apiFetch<unknown>(`/api/lists/${id}/items`, { method: 'POST', body }),
  removeListItem: (id: string, productId: string, variantId?: string | null) =>
    apiFetch<void>(`/api/lists/${id}/items/${productId}`, {
      method: 'DELETE',
      ...(variantId ? { params: { variantId } } : {}),
    }),
  shareList: (id: string) => apiFetch<{ shareUrl: string }>(`/api/lists/${id}/share`, { method: 'POST' }),
  unshareList: (id: string) => apiFetch<void>(`/api/lists/${id}/share`, { method: 'DELETE' }),
  sharedList: (token: string) => apiFetch<SharedList>(`/api/lists/shared/${encodeURIComponent(token)}`),

  notifications: (page = 0, unreadOnly = false) =>
    apiFetch<NotificationPage>('/api/notifications', { params: { page, unreadOnly } }),
  markRead: (id: string) => apiFetch<AppNotification | void>(`/api/notifications/${id}/read`, { method: 'POST' }),
  markAllRead: () => apiFetch<void>('/api/notifications/read-all', { method: 'POST' }),

  preferences: () => apiFetch<Preferences>('/api/users/me/preferences'),
  savePreferences: (body: Preferences) => apiFetch<Preferences>('/api/users/me/preferences', { method: 'PUT', body }),

  vapidKey: () => apiFetch<{ publicKey?: string; key?: string } | string>('/api/push/vapid-public-key'),
  subscribePush: (body: { endpoint: string; keys: { p256dh: string; auth: string } }) =>
    apiFetch<void>('/api/push/subscriptions', { method: 'POST', body }),
  unsubscribePush: (endpoint: string) =>
    apiFetch<void>('/api/push/subscriptions', { method: 'DELETE', body: { endpoint } }),
}
