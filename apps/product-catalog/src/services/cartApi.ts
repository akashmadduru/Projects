/**
 * Cart and wishlist API client (cart-service via gateway)
 */

import { apiFetch } from '../lib/http'
import type { CartLineRequest, CartResponse, WishlistItemResponse, WishlistLine } from '../types'

export class CartApiClient {
  getCart(): Promise<CartResponse> {
    return apiFetch<CartResponse>('/api/cart')
  }

  addItem(line: CartLineRequest): Promise<CartResponse> {
    return apiFetch<CartResponse>('/api/cart/items', { method: 'POST', body: line })
  }

  setQuantity(productId: string, quantity: number): Promise<CartResponse> {
    return apiFetch<CartResponse>(`/api/cart/items/${productId}`, { method: 'PUT', body: { quantity } })
  }

  removeItem(productId: string): Promise<void> {
    return apiFetch<void>(`/api/cart/items/${productId}`, { method: 'DELETE' })
  }

  clearCart(): Promise<void> {
    return apiFetch<void>('/api/cart', { method: 'DELETE' })
  }

  mergeCart(items: CartLineRequest[]): Promise<CartResponse> {
    return apiFetch<CartResponse>('/api/cart/merge', { method: 'POST', body: { items } })
  }

  saveForLater(productId: string): Promise<CartResponse> {
    return apiFetch<CartResponse>(`/api/cart/items/${productId}/save-for-later`, { method: 'POST' })
  }

  moveSavedToCart(productId: string): Promise<CartResponse> {
    return apiFetch<CartResponse>(`/api/cart/saved/${productId}/move-to-cart`, { method: 'POST' })
  }

  removeSaved(productId: string): Promise<CartResponse> {
    return apiFetch<CartResponse>(`/api/cart/saved/${productId}`, { method: 'DELETE' })
  }

  getWishlist(): Promise<WishlistItemResponse[]> {
    return apiFetch<WishlistItemResponse[]>('/api/wishlist')
  }

  saveWishlistItem(line: WishlistLine): Promise<WishlistItemResponse> {
    const { productId, ...body } = line
    return apiFetch<WishlistItemResponse>(`/api/wishlist/${productId}`, { method: 'PUT', body })
  }

  removeWishlistItem(productId: string): Promise<void> {
    return apiFetch<void>(`/api/wishlist/${productId}`, { method: 'DELETE' })
  }

  mergeWishlist(items: WishlistLine[]): Promise<WishlistItemResponse[]> {
    return apiFetch<WishlistItemResponse[]>('/api/wishlist/merge', { method: 'POST', body: { items } })
  }
}

export const cartApi = new CartApiClient()
