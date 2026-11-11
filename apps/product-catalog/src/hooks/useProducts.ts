/**
 * Fetches one page of the catalog for a query, falling back to demo data when the API is down
 */

import { useEffect, useState } from 'react'
import { usePaginationStore } from '../stores'
import { productApi } from '../services/productApi'
import { dummyProducts } from '../lib/dummyProducts'
import { queryLocally } from '../lib/localCatalog'
import type { ApiError, ProductQuery, ProductResponse } from '../types'

type ProductsState =
  | { status: 'idle'; isDummyData?: boolean }
  | { status: 'loading'; isDummyData?: boolean }
  | { status: 'success'; data: ProductResponse; isDummyData: boolean }
  | { status: 'error'; error: ApiError; isDummyData?: boolean }

export function useProducts(query: ProductQuery = {}, page = 0, sizeOverride?: number): ProductsState {
  const storedSize = usePaginationStore((state) => state.size)
  const size = sizeOverride ?? storedSize
  const [state, setState] = useState<ProductsState>({ status: 'idle' })
  // Stable dependency for an object the caller rebuilds on every render.
  const queryKey = JSON.stringify(query)

  useEffect(() => {
    let mounted = true
    const currentQuery = JSON.parse(queryKey) as ProductQuery

    async function fetchProducts() {
      setState({ status: 'loading' })
      try {
        const data = await productApi.list(page, size, currentQuery)
        if (mounted) setState({ status: 'success', data, isDummyData: false })
      } catch {
        if (mounted) {
          setState({
            status: 'success',
            data: queryLocally(dummyProducts, currentQuery, page, size),
            isDummyData: true,
          })
        }
      }
    }

    void fetchProducts()
    return () => {
      mounted = false
    }
  }, [queryKey, page, size])

  return state
}
