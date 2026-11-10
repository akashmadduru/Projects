/**
 * Custom hook for fetching product categories
 */

import { useEffect, useState } from 'react'
import { productApi } from '../services/productApi'
import type { Category, ApiError } from '../types'

type CategoriesState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: Category[] }
  | { status: 'error'; error: ApiError }

export function useCategories(): CategoriesState {
  const [state, setState] = useState<CategoriesState>({ status: 'idle' })

  useEffect(() => {
    let mounted = true

    async function fetchCategories() {
      setState({ status: 'loading' })
      try {
        const data = await productApi.getCategories()
        if (mounted) {
          setState({ status: 'success', data })
        }
      } catch (error) {
        if (mounted) {
          setState({ status: 'error', error: error as ApiError })
        }
      }
    }

    void fetchCategories()

    return () => {
      mounted = false
    }
  }, [])

  return state
}
