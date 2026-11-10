/**
 * Zustand filter store for managing product filters with localStorage persistence
 */

import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export interface FilterState {
  categoryId?: string
  minPrice?: number
  maxPrice?: number
  minRating?: number
  sortBy?: 'price-asc' | 'price-desc' | 'rating' | 'newest'

  setCategoryId: (categoryId: string | undefined) => void
  setPriceRange: (min: number | undefined, max: number | undefined) => void
  setMinRating: (rating: number | undefined) => void
  setSortBy: (sortBy: FilterState['sortBy']) => void
  reset: () => void
}


export const useFilterStore = create<FilterState>()(
  persist(
    (set) => ({
      categoryId: undefined,
      minPrice: undefined,
      maxPrice: undefined,
      minRating: undefined,
      sortBy: undefined,

      setCategoryId: (categoryId: string | undefined) => {
        set({ categoryId })
      },

      setPriceRange: (min: number | undefined, max: number | undefined) => {
        set({ minPrice: min, maxPrice: max })
      },

      setMinRating: (rating: number | undefined) => {
        set({ minRating: rating })
      },

      setSortBy: (sortBy: FilterState['sortBy']) => {
        set({ sortBy })
      },

      reset: () => {
        set({
          categoryId: undefined,
          minPrice: undefined,
          maxPrice: undefined,
          minRating: undefined,
          sortBy: undefined,
        })
      },
    }),
    {
      name: 'filter-store',
      version: 1,
    },
  ),
)
