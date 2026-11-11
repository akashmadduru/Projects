/**
 * Transient UI state shared across the layout: the cart drawer and toast notifications
 */

import { create } from 'zustand'

export interface Toast {
  id: number
  message: string
}

interface UiState {
  cartOpen: boolean
  openCart: () => void
  closeCart: () => void
  toasts: Toast[]
  notify: (message: string) => void
  dismiss: (id: number) => void
}

export const TOAST_MS = 3000
let nextToastId = 1

export const useUiStore = create<UiState>()((set) => ({
  cartOpen: false,
  openCart: () => set({ cartOpen: true }),
  closeCart: () => set({ cartOpen: false }),

  toasts: [],
  notify: (message) => {
    const id = nextToastId++
    // Keep at most three on screen; the newest matters most.
    set((state) => ({ toasts: [...state.toasts.slice(-2), { id, message }] }))
    setTimeout(() => set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })), TOAST_MS)
  },
  dismiss: (id) => set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })),
}))
