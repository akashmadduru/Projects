/**
 * Horizontally scrolling strip of top-level categories under the header
 */

import { Link, useLocation } from 'react-router-dom'
import { parseCatalogParams, toSearchString, useCategories } from '../hooks'

export function CategoryNav() {
  const { tree } = useCategories()
  const location = useLocation()
  const onCatalog = location.pathname === '/'
  const { query } = parseCatalogParams(new URLSearchParams(onCatalog ? location.search : ''))

  // The strip highlights the root of whatever category is selected, even a subcategory.
  const selectedRoot = tree.find(
    (root) => root.id === query.categoryId || root.children.some((c) => c.id === query.categoryId),
  )?.id

  const href = (categoryId?: string) => ({
    pathname: '/',
    search: toSearchString({ q: query.q, sort: query.sort, categoryId }),
  })

  const item = (active: boolean) =>
    `shrink-0 whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition-colors ${
      active
        ? 'border-accent text-on-surface'
        : 'border-transparent text-on-surface-muted hover:text-on-surface'
    }`

  return (
    <nav aria-label="Categories" className="border-b border-outline bg-surface">
      <ul className="mx-auto flex max-w-[1400px] gap-1 overflow-x-auto px-4 sm:px-6 lg:px-8 [scrollbar-width:none]">
        <li>
          <Link to={href()} className={item(onCatalog && !query.categoryId)} aria-current={onCatalog && !query.categoryId ? 'page' : undefined}>
            All products
          </Link>
        </li>
        {tree.map((root) => (
          <li key={root.id}>
            <Link
              to={href(root.id)}
              className={item(selectedRoot === root.id)}
              aria-current={selectedRoot === root.id ? 'page' : undefined}
            >
              {root.name}
            </Link>
          </li>
        ))}
        <li>
          <Link
            to={{ pathname: '/', search: toSearchString({ minDiscount: 20, sort: 'discount' }) }}
            className={`${item(onCatalog && query.minDiscount === 20 && query.sort === 'discount')} !text-accent-strong`}
          >
            Top deals
          </Link>
        </li>
      </ul>
    </nav>
  )
}
