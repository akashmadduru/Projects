/**
 * Brand / attribute facet counts for the current catalog query. Silent on failure (the panel just hides the sections).
 */

import { useEffect, useState } from 'react'
import { discoveryApi } from '../services/discoveryApi'
import type { FacetsResponse, ProductQuery } from '../types'

export function useFacets(query: ProductQuery): FacetsResponse | undefined {
  const [facets, setFacets] = useState<FacetsResponse | undefined>()
  const key = JSON.stringify(query)
  useEffect(() => {
    let live = true
    discoveryApi
      .facets(JSON.parse(key) as ProductQuery)
      .then((res) => {
        if (live) setFacets(res)
      })
      .catch(() => {
        if (live) setFacets(undefined)
      })
    return () => {
      live = false
    }
  }, [key])
  return facets
}
