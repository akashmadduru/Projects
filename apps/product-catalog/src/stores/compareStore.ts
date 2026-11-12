/**
 * Zustand compare list: up to 4 products, persisted to localStorage.
 */

import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export const COMPARE_MAX = 4

export interface CompareEntry {
  id: string
  slug?: string | null
  name: string
  image?: string
}

interface CompareState {
  items: CompareEntry[]
  /** Returns false when the list is full. */
  add: (entry: CompareEntry) => boolean
  remove: (id: string) => void
  clear: () => void
}

export const useCompareStore = create<CompareState>()(
  persist(
    (set, get) => ({
      items: [],
      add: (entry) => {
        const { items } = get()
        if (items.some((i) => i.id === entry.id)) return true
        if (items.length >= COMPARE_MAX) return false
        set({ items: [...items, entry] })
        return true
      },
      remove: (id) => set((s) => ({ items: s.items.filter((i) => i.id !== id) })),
      clear: () => set({ items: [] }),
    }),
    { name: 'compare-store', version: 1 },
  ),
)
