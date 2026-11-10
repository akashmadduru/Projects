/**
 * Custom hook for fetching product ratings
 */

import { useEffect, useState } from 'react'
import { productApi } from '../services/productApi'
import type { ProductRating, PagedModel, ApiError } from '../types'

type RatingsState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: PagedModel<ProductRating> }
  | { status: 'error'; error: ApiError }

export function useProductRatings(productId: string): RatingsState {
  const [state, setState] = useState<RatingsState>({ status: 'idle' })

  useEffect(() => {
    if (!productId) {
      setState({ status: 'idle' })
      return
    }

    let mounted = true

    async function fetchRatings() {
      setState({ status: 'loading' })
      try {
        const data = await productApi.getRatings(productId)
        if (mounted) {
          setState({ status: 'success', data })
        }
      } catch (error) {
        if (mounted) {
          setState({ status: 'error', error: error as ApiError })
        }
      }
    }

    void fetchRatings()

    return () => {
      mounted = false
    }
  }, [productId])

  return state
}
