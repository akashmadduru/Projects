/**
 * Zustand wishlist store for managing saved items with localStorage persistence
 */

import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export interface WishlistItem {
  productId: string
  addedAt: number
}

export interface WishlistState {
  items: WishlistItem[]
  addItem: (productId: string) => void
  removeItem: (productId: string) => void
  toggleItem: (productId: string) => void
  isInWishlist: (productId: string) => boolean
  clearWishlist: () => void
  getItemCount: () => number
}

export const useWishlistStore = create<WishlistState>()(
  persist(
    (set, get) => ({
      items: [],

      addItem: (productId: string) => {
        set((state) => {
          const exists = state.items.some((item) => item.productId === productId)
          if (exists) return state
          return {
            items: [...state.items, { productId, addedAt: Date.now() }],
          }
        })
      },

      removeItem: (productId: string) => {
        set((state) => ({
          items: state.items.filter((item) => item.productId !== productId),
        }))
      },

      toggleItem: (productId: string) => {
        set((state) => {
          const exists = state.items.some((item) => item.productId === productId)
          if (exists) {
            return {
              items: state.items.filter((item) => item.productId !== productId),
            }
          }
          return {
            items: [...state.items, { productId, addedAt: Date.now() }],
          }
        })
      },

      isInWishlist: (productId: string) => {
        return get().items.some((item) => item.productId === productId)
      },

      clearWishlist: () => {
        set({ items: [] })
      },

      getItemCount: () => {
        return get().items.length
      },
    }),
    {
      name: 'wishlist-store',
      version: 1,
    },
  ),
)
