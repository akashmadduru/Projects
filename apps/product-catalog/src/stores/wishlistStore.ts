/**
 * Zustand wishlist store.
 * Anonymous: local-first, persisted to localStorage.
 * Signed in: the server list is the source of truth; mutations are optimistic with rollback
 * and nothing is persisted locally.
 */

import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { errorMessage } from '../lib/errors'
import { cartApi } from '../services/cartApi'
import type { WishlistItemResponse, WishlistLine } from '../types'
import { useUiStore } from './uiStore'

/** What the server needs to store a wishlist row without calling product-service. */
export interface WishlistSnapshot {
  name: string
  price: number
  currency: string
  imageUrl?: string
  variantLabel?: string
}

export interface WishlistItem {
  productId: string
  variantId?: string
  addedAt: number
  snapshot?: WishlistSnapshot
}

export interface WishlistState {
  items: WishlistItem[]
  /** True while a signed-in user's wishlist is mirrored from the server. Not persisted. */
  serverBacked: boolean
  addItem: (productId: string, snapshot?: WishlistSnapshot, variantId?: string) => void
  removeItem: (productId: string, variantId?: string) => void
  toggleItem: (productId: string, snapshot?: WishlistSnapshot, variantId?: string) => void
  isInWishlist: (productId: string, variantId?: string) => boolean
  clearWishlist: () => void
  getItemCount: () => number
  attachServer: () => Promise<void>
  detachServer: () => void
}

/** Server rejects wishlist merge payloads above 200 items. */
const MERGE_CHUNK = 200

function fromServer(rows: WishlistItemResponse[]): WishlistItem[] {
  return rows.map((row) => ({
    productId: row.productId,
    variantId: row.variantId ?? undefined,
    addedAt: Date.parse(row.addedAt) || Date.now(),
    snapshot: {
      name: row.name,
      price: Number(row.price),
      currency: row.currency,
      imageUrl: row.imageUrl ?? undefined,
      variantLabel: row.variantLabel ?? undefined,
    },
  }))
}

function toLine(item: WishlistItem): WishlistLine | null {
  if (!item.snapshot) return null
  return {
    productId: item.productId,
    name: item.snapshot.name,
    price: item.snapshot.price,
    currency: item.snapshot.currency,
    imageUrl: item.snapshot.imageUrl ?? null,
    variantId: item.variantId ?? null,
    variantLabel: item.snapshot.variantLabel ?? null,
  }
}

const same = (item: WishlistItem, productId: string, variantId?: string) =>
  item.productId === productId && (item.variantId ?? undefined) === variantId

export const useWishlistStore = create<WishlistState>()(
  persist(
    (set, get) => {
      let mutationSeq = 0

      const mutate = (optimistic: (items: WishlistItem[]) => WishlistItem[], call: () => Promise<unknown>) => {
        const previous = get().items
        const seq = ++mutationSeq
        set({ items: optimistic(previous) })
        call().catch((error: unknown) => {
          useUiStore.getState().notify(errorMessage(error))
          // Reconcile with the server rather than guessing a rollback when mutations overlap.
          const reconcileSeq = ++mutationSeq
          cartApi
            .getWishlist()
            .then((rows) => {
              if (reconcileSeq === mutationSeq) set({ items: fromServer(rows) })
            })
            .catch(() => {
              if (reconcileSeq === mutationSeq) set({ items: previous })
            })
        })
      }

      const add = (productId: string, snapshot?: WishlistSnapshot, variantId?: string) => {
        const apply = (items: WishlistItem[]): WishlistItem[] =>
          items.some((item) => same(item, productId, variantId))
            ? items
            : [...items, { productId, variantId, addedAt: Date.now(), snapshot }]
        if (!get().serverBacked) {
          set((state) => ({ items: apply(state.items) }))
          return
        }
        if (!snapshot) return // the server needs name/price; every UI caller supplies one
        mutate(apply, () =>
          cartApi.saveWishlistItem({
            productId,
            name: snapshot.name,
            price: snapshot.price,
            currency: snapshot.currency,
            imageUrl: snapshot.imageUrl ?? null,
            variantId: variantId ?? null,
            variantLabel: snapshot.variantLabel ?? null,
          }),
        )
      }

      const remove = (productId: string, variantId?: string) => {
        const apply = (items: WishlistItem[]) => items.filter((item) => !same(item, productId, variantId))
        if (!get().serverBacked) {
          set((state) => ({ items: apply(state.items) }))
          return
        }
        mutate(apply, () => cartApi.removeWishlistItem(productId, variantId))
      }

      return {
        items: [],
        serverBacked: false,

        addItem: add,
        removeItem: remove,

        toggleItem: (productId, snapshot, variantId) => {
          if (get().items.some((item) => same(item, productId, variantId))) remove(productId, variantId)
          else add(productId, snapshot, variantId)
        },

        isInWishlist: (productId, variantId) => get().items.some((item) => same(item, productId, variantId)),

        clearWishlist: () => {
          if (!get().serverBacked) {
            set({ items: [] })
            return
          }
          for (const item of get().items) remove(item.productId, item.variantId)
        },

        getItemCount: () => get().items.length,

        attachServer: async () => {
          const local = get().serverBacked ? [] : get().items
          const lines = local.map(toLine).filter((line): line is WishlistLine => line !== null)
          mutationSeq++
          try {
            let rows: WishlistItemResponse[] | null = null
            for (let i = 0; i < lines.length; i += MERGE_CHUNK) {
              rows = await cartApi.mergeWishlist(lines.slice(i, i + MERGE_CHUNK))
            }
            set({ items: fromServer(rows ?? (await cartApi.getWishlist())), serverBacked: true })
          } catch (error) {
            useUiStore.getState().notify(errorMessage(error))
            // Keep unmerged local items (the merge is a union, so retrying on next sign-in is safe).
            if (lines.length > 0) return
            try {
              set({ items: fromServer(await cartApi.getWishlist()), serverBacked: true })
            } catch {
              set({ items: [], serverBacked: true })
            }
          }
        },

        detachServer: () => {
          mutationSeq++
          set({ items: [], serverBacked: false })
        },
      }
    },
    {
      name: 'wishlist-store',
      version: 1,
      partialize: (state) => ({ items: state.serverBacked ? [] : state.items }),
    },
  ),
)
