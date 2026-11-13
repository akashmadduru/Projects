/**
 * Zustand cart store.
 * Anonymous: local-first, persisted to localStorage.
 * Signed in: the server cart is the source of truth; mutations are optimistic with rollback
 * and nothing is persisted locally.
 */

import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { errorMessage } from '../lib/errors'
import { cartApi } from '../services/cartApi'
import type { CartLineRequest, CartResponse, SavedItemResponse } from '../types'
import { useUiStore } from './uiStore'

/** What the cart drawer shows without refetching each product. Captured at add time. */
export interface CartSnapshot {
  name: string
  priceAmount: number
  priceCurrency: string
  image?: string
}

export interface CartItem {
  productId: string
  /** Null/undefined for products without variants. */
  variantId?: string
  variantLabel?: string
  /** Marketplace offer the line was added from (undefined = buy box). */
  offerId?: string
  quantity: number
  addedAt: number
  snapshot?: CartSnapshot
}

export interface CartVariant {
  variantId: string
  variantLabel: string
}

/** A cart line is identified by product, optional variant and offer (seller). An omitted offerId matches any offer. */
export const sameLine = (
  item: { productId: string; variantId?: string | null; offerId?: string | null },
  productId: string,
  variantId?: string | null,
  offerId?: string | null,
) =>
  item.productId === productId &&
  (item.variantId ?? null) === (variantId ?? null) &&
  (offerId === undefined || (item.offerId ?? null) === (offerId ?? null))

export interface CartState {
  items: CartItem[]
  /** True while a signed-in user's cart is mirrored from the server. Not persisted. */
  serverBacked: boolean
  /** Save-for-later lines; signed-in users only, mirrored from the server. */
  saved: SavedItemResponse[]
  saveForLater: (productId: string, variantId?: string) => Promise<void>
  moveSavedToCart: (productId: string, variantId?: string) => Promise<void>
  removeSaved: (productId: string, variantId?: string) => Promise<void>
  addItem: (productId: string, quantity: number, snapshot?: CartSnapshot, variant?: CartVariant, offerId?: string) => void
  removeItem: (productId: string, variantId?: string, offerId?: string) => void
  updateQuantity: (productId: string, quantity: number, variantId?: string, offerId?: string) => void
  clearCart: () => void
  getItemCount: () => number
  getTotalItems: () => number
  /** After login: merge the anonymous cart into the account, then adopt the server cart. */
  attachServer: () => Promise<void>
  /** On logout: drop everything and return to anonymous local mode. */
  detachServer: () => void
}

const MAX_QUANTITY = 99
/** Server rejects merge payloads above 100 lines. */
const MERGE_CHUNK = 100

function clampQuantity(quantity: number): number {
  return Math.min(Math.max(Math.trunc(quantity), 1), MAX_QUANTITY)
}

function fromServer(cart: CartResponse): CartItem[] {
  return cart.items.map((line) => ({
    productId: line.productId,
    ...(line.variantId ? { variantId: line.variantId, variantLabel: line.variantLabel ?? undefined } : {}),
    ...(line.offerId ? { offerId: line.offerId } : {}),
    quantity: line.quantity,
    addedAt: Date.parse(line.addedAt) || Date.now(),
    snapshot: {
      name: line.name,
      priceAmount: Number(line.unitPrice),
      priceCurrency: line.currency,
      image: line.imageUrl ?? undefined,
    },
  }))
}

function toLine(item: CartItem): CartLineRequest | null {
  if (!item.snapshot) return null
  return {
    productId: item.productId,
    ...(item.variantId ? { variantId: item.variantId, variantLabel: item.variantLabel ?? null } : {}),
    ...(item.offerId ? { offerId: item.offerId } : {}),
    quantity: clampQuantity(item.quantity),
    name: item.snapshot.name,
    unitPrice: item.snapshot.priceAmount,
    currency: item.snapshot.priceCurrency,
    imageUrl: item.snapshot.image ?? null,
  }
}

export const useCartStore = create<CartState>()(
  persist(
    (set, get) => {
      // Only the newest server response may overwrite state; older ones are stale.
      let mutationSeq = 0

      /** Applies `optimistic` now, then reconciles with (or rolls back from) the server call. */
      const mutate = (optimistic: (items: CartItem[]) => CartItem[], call: () => Promise<CartResponse | void>) => {
        const previous = get().items
        const seq = ++mutationSeq
        set({ items: optimistic(previous) })
        call()
          .then((cart) => {
            if (cart && seq === mutationSeq) set({ items: fromServer(cart), saved: cart.savedItems ?? get().saved })
          })
          .catch((error: unknown) => {
            if (seq === mutationSeq) set({ items: previous })
            useUiStore.getState().notify(errorMessage(error))
          })
      }

      /** Non-optimistic mutation: wait for the server cart, then adopt it (items and saved lines). */
      const applyServer = async (call: () => Promise<CartResponse>, success?: string) => {
        if (!get().serverBacked) return
        const seq = ++mutationSeq
        try {
          const cart = await call()
          if (seq === mutationSeq) set({ items: fromServer(cart), saved: cart.savedItems ?? [] })
          if (success) useUiStore.getState().notify(success)
        } catch (error) {
          useUiStore.getState().notify(errorMessage(error))
        }
      }

      return {
        items: [],
        serverBacked: false,
        saved: [],

        saveForLater: (productId, variantId) =>
          applyServer(() => cartApi.saveForLater(productId, variantId), 'Saved for later'),
        moveSavedToCart: (productId, variantId) =>
          applyServer(() => cartApi.moveSavedToCart(productId, variantId), 'Moved to your cart'),
        removeSaved: (productId, variantId) => applyServer(() => cartApi.removeSaved(productId, variantId)),

        addItem: (productId, quantity, snapshot, variant, offerId) => {
          const apply = (items: CartItem[]): CartItem[] => {
            const existing = items.find((item) => sameLine(item, productId, variant?.variantId, offerId ?? null))
            if (existing) {
              return items.map((item) =>
                item === existing
                  ? { ...item, quantity: clampQuantity(item.quantity + quantity), snapshot: snapshot ?? item.snapshot }
                  : item,
              )
            }
            return [
              ...items,
              {
                productId,
                ...(variant ?? {}),
                ...(offerId ? { offerId } : {}),
                quantity: clampQuantity(quantity),
                addedAt: Date.now(),
                snapshot,
              },
            ]
          }

          if (!get().serverBacked) {
            set((state) => ({ items: apply(state.items) }))
            return
          }
          if (!snapshot) return // the server needs name/price; every UI caller supplies one
          mutate(apply, () =>
            cartApi.addItem({
              productId,
              quantity,
              name: snapshot.name,
              unitPrice: snapshot.priceAmount,
              currency: snapshot.priceCurrency,
              imageUrl: snapshot.image ?? null,
              ...(variant ? { variantId: variant.variantId, variantLabel: variant.variantLabel } : {}),
              ...(offerId ? { offerId } : {}),
            }),
          )
        },

        removeItem: (productId, variantId, offerId) => {
          const apply = (items: CartItem[]) => items.filter((item) => !sameLine(item, productId, variantId, offerId))
          if (!get().serverBacked) {
            set((state) => ({ items: apply(state.items) }))
            return
          }
          mutate(apply, () => cartApi.removeItem(productId, variantId, offerId))
        },

        // A quantity of 0 removes the line rather than leaving an empty row in the cart.
        updateQuantity: (productId, quantity, variantId, offerId) => {
          if (quantity <= 0) {
            get().removeItem(productId, variantId, offerId)
            return
          }
          const apply = (items: CartItem[]) =>
            items.map((item) => (sameLine(item, productId, variantId, offerId) ? { ...item, quantity: clampQuantity(quantity) } : item))
          if (!get().serverBacked) {
            set((state) => ({ items: apply(state.items) }))
            return
          }
          mutate(apply, () => cartApi.setQuantity(productId, clampQuantity(quantity), variantId, offerId))
        },

        clearCart: () => {
          if (!get().serverBacked) {
            set({ items: [] })
            return
          }
          mutate(() => [], () => cartApi.clearCart())
        },

        getItemCount: () => get().items.length,

        getTotalItems: () => get().items.reduce((sum, item) => sum + item.quantity, 0),

        attachServer: async () => {
          const local = get().serverBacked ? [] : get().items
          const mergeable = local.filter((item) => item.snapshot)
          mutationSeq++
          try {
            let cart: CartResponse | null = null
            for (let i = 0; i < mergeable.length; i += MERGE_CHUNK) {
              const chunk = mergeable.slice(i, i + MERGE_CHUNK)
              const lines = chunk.map(toLine).filter((line): line is CartLineRequest => line !== null)
              cart = await cartApi.mergeCart(lines)
              // Merged lines now live on the server; drop them locally so a retry cannot double-count them.
              set({ items: get().items.filter((item) => !chunk.some((c) => sameLine(item, c.productId, c.variantId, c.offerId ?? null))) })
            }
            const adopted = cart ?? (await cartApi.getCart())
            set({ items: fromServer(adopted), saved: adopted.savedItems ?? [], serverBacked: true })
          } catch (error) {
            useUiStore.getState().notify(errorMessage(error))
            // Never discard anonymous items that were not merged: stay in local mode and retry on next sign-in.
            if (get().items.length > 0) return
            try {
              const adopted = await cartApi.getCart()
              set({ items: fromServer(adopted), saved: adopted.savedItems ?? [], serverBacked: true })
            } catch {
              set({ items: [], serverBacked: true })
            }
          }
        },

        detachServer: () => {
          mutationSeq++
          set({ items: [], saved: [], serverBacked: false })
        },
      }
    },
    {
      name: 'cart-store',
      version: 1,
      // Never leave a signed-in user's cart behind in localStorage.
      partialize: (state) => ({ items: state.serverBacked ? [] : state.items }),
    },
  ),
)
