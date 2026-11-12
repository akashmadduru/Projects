/**
 * Fetches one product (with its full image list), falling back to demo data when the API is down
 */

import { useEffect, useState } from 'react'
import { productApi } from '../services/productApi'
import { dummyProducts } from '../lib/dummyProducts'
import type { Product } from '../types'

type ProductState =
  | { status: 'loading' }
  | { status: 'success'; data: Product; isDummyData: boolean }
  | { status: 'not-found' }

export function useProduct(id: string | undefined, slug?: string): ProductState {
  const [state, setState] = useState<ProductState>({ status: 'loading' })

  useEffect(() => {
    let mounted = true
    setState({ status: 'loading' })

    async function fetchProduct() {
      if (!id && !slug) {
        setState({ status: 'not-found' })
        return
      }
      try {
        const data = slug ? await productApi.bySlug(slug) : await productApi.detail(id as string)
        if (mounted) setState({ status: 'success', data, isDummyData: false })
      } catch (error) {
        if (!mounted) return
        const status = (error as { status?: number }).status
        const fallback = status === 404 ? undefined : dummyProducts.find((p) => (slug ? p.slug === slug : p.id === id))
        setState(fallback ? { status: 'success', data: fallback, isDummyData: true } : { status: 'not-found' })
      }
    }

    void fetchProduct()
    return () => {
      mounted = false
    }
  }, [id, slug])

  return state
}
