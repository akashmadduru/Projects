/**
 * Zustand pagination store - page and page size management
 */

import { create } from 'zustand'
import { subscribeWithSelector } from 'zustand/middleware'

interface PaginationState {
  page: number
  size: number
  setPage: (page: number) => void
  setSize: (size: number) => void
  reset: () => void
}

export const usePaginationStore = create<PaginationState>()(
  subscribeWithSelector((set) => ({
    page: 0,
    size: 20,
    setPage: (page: number) => {
      set({ page: Math.max(0, page) })
    },
    setSize: (size: number) => {
      set({ size: Math.max(1, size), page: 0 })
    },
    reset: () => {
      set({ page: 0, size: 20 })
    },
  })),
)
