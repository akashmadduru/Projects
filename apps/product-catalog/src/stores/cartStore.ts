/**
 * Zustand cart store - persisted to localStorage
 */

import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/** What the cart drawer shows without refetching each product. Captured at add time. */
export interface CartSnapshot {
  name: string
  priceAmount: number
  priceCurrency: string
  image?: string
}

export interface CartItem {
  productId: string
  quantity: number
  addedAt: number
  snapshot?: CartSnapshot
}

export interface CartState {
  items: CartItem[]
  addItem: (productId: string, quantity: number, snapshot?: CartSnapshot) => void
  removeItem: (productId: string) => void
  updateQuantity: (productId: string, quantity: number) => void
  clearCart: () => void
  getItemCount: () => number
  getTotalItems: () => number
}

export const useCartStore = create<CartState>()(
  persist(
    (set, get) => ({
      items: [],

      addItem: (productId, quantity, snapshot) => {
        set((state) => {
          const existing = state.items.find((item) => item.productId === productId)
          if (existing) {
            return {
              items: state.items.map((item) =>
                item.productId === productId
                  ? { ...item, quantity: item.quantity + quantity, snapshot: snapshot ?? item.snapshot }
                  : item,
              ),
            }
          }
          return { items: [...state.items, { productId, quantity, addedAt: Date.now(), snapshot }] }
        })
      },

      removeItem: (productId) => {
        set((state) => ({ items: state.items.filter((item) => item.productId !== productId) }))
      },

      // A quantity of 0 removes the line rather than leaving an empty row in the cart.
      updateQuantity: (productId, quantity) => {
        set((state) => ({
          items:
            quantity <= 0
              ? state.items.filter((item) => item.productId !== productId)
              : state.items.map((item) => (item.productId === productId ? { ...item, quantity } : item)),
        }))
      },

      clearCart: () => set({ items: [] }),

      getItemCount: () => get().items.length,

      getTotalItems: () => get().items.reduce((sum, item) => sum + item.quantity, 0),
    }),
    {
      name: 'cart-store',
      version: 1,
    },
  ),
)
