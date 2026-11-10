/**
 * Custom hook for fetching product inventory/stock status
 */

import { useEffect, useState } from 'react'
import { productApi } from '../services/productApi'
import type { InventoryStatus, ApiError } from '../types'

type InventoryState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: InventoryStatus }
  | { status: 'error'; error: ApiError }

export function useInventory(productId: string): InventoryState {
  const [state, setState] = useState<InventoryState>({ status: 'idle' })

  useEffect(() => {
    if (!productId) {
      setState({ status: 'idle' })
      return
    }

    let mounted = true

    async function fetchInventory() {
      setState({ status: 'loading' })
      try {
        const data = await productApi.getInventory(productId)
        if (mounted) {
          setState({ status: 'success', data })
        }
      } catch (error) {
        if (mounted) {
          setState({ status: 'error', error: error as ApiError })
        }
      }
    }

    void fetchInventory()

    return () => {
      mounted = false
    }
  }, [productId])

  return state
}
