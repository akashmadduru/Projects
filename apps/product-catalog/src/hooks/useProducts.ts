/**
 * Custom hook for fetching paginated products with fallback to dummy data
 */

import { useEffect, useState } from 'react'
import { usePaginationStore } from '../stores'
import { productApi } from '../services/productApi'
import { dummyProducts } from '../lib/dummyProducts'
import type { ApiError, ProductResponse } from '../types'

type ProductsState =
  | { status: 'idle'; isDummyData?: boolean }
  | { status: 'loading'; isDummyData?: boolean }
  | { status: 'success'; data: ProductResponse; isDummyData: boolean }
  | { status: 'error'; error: ApiError; isDummyData?: boolean }

export function useProducts(): ProductsState {
  const { page, size } = usePaginationStore()
  const [state, setState] = useState<ProductsState>({ status: 'idle' })

  useEffect(() => {
    let mounted = true

    async function fetchProducts() {
      setState({ status: 'loading' })
      try {
        const data = await productApi.list(page, size)
        if (mounted) {
          setState({ status: 'success', data, isDummyData: false })
        }
      } catch (error) {
        if (mounted) {
          // Fallback to dummy products on error
          const dummyStart = page * size
          const dummyEnd = dummyStart + size
          const dummyContent = dummyProducts.slice(dummyStart, dummyEnd)

          const fallbackData: ProductResponse = {
            content: dummyContent,
            pageable: {
              pageNumber: page,
              pageSize: size,
            },
            totalElements: dummyProducts.length,
            totalPages: Math.ceil(dummyProducts.length / size),
            isFirst: page === 0,
            isLast: dummyEnd >= dummyProducts.length,
          }

          setState({
            status: 'success',
            data: fallbackData,
            isDummyData: true,
          })
        }
      }
    }

    void fetchProducts()

    return () => {
      mounted = false
    }
  }, [page, size])

  return state
}
