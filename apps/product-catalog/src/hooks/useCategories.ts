/**
 * Loads the category tree, falling back to the demo-data categories when the API is down
 */

import { useEffect, useState } from 'react'
import { productApi } from '../services/productApi'
import type { Category } from '../types'

export interface CategoryNode extends Category {
  children: Category[]
}

const demoCategory = (id: string, name: string): Category => ({
  id,
  name,
  description: null,
  parentCategoryId: null,
  depthLevel: 0,
  active: true,
  version: 0,
})

// Matches the categoryId values used by lib/dummyProducts.
export const DEMO_CATEGORIES: Category[] = [
  demoCategory('ELECTRONICS', 'Electronics'),
  demoCategory('ACCESSORIES', 'Accessories'),
  demoCategory('HOME', 'Home'),
]

export function buildCategoryTree(categories: Category[]): CategoryNode[] {
  const active = categories.filter((c) => c.active)
  return active
    .filter((c) => !c.parentCategoryId)
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((root) => ({
      ...root,
      children: active
        .filter((c) => c.parentCategoryId === root.id)
        .sort((a, b) => a.name.localeCompare(b.name)),
    }))
}

// Module-level cache: the header and the filter panel both need the tree on every page.
let cached: Promise<Category[]> | undefined

function loadCategories(): Promise<Category[]> {
  cached ??= productApi.getCategories().catch(() => {
    cached = undefined
    return DEMO_CATEGORIES
  })
  return cached
}

export function useCategories(): { categories: Category[]; tree: CategoryNode[]; loading: boolean } {
  const [categories, setCategories] = useState<Category[] | undefined>()

  useEffect(() => {
    let mounted = true
    void loadCategories().then((data) => {
      if (mounted) setCategories(data)
    })
    return () => {
      mounted = false
    }
  }, [])

  const list = categories ?? []
  return { categories: list, tree: buildCategoryTree(list), loading: categories === undefined }
}
