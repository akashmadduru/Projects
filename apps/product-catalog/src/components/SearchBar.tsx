/**
 * Header product search. Submitting navigates to the catalog with ?q=, keeping the other filters.
 */

import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { CloseIcon, SearchIcon } from './Icons'
import { parseCatalogParams, toSearchString } from '../hooks'

export function SearchBar() {
  const navigate = useNavigate()
  const location = useLocation()
  const onCatalog = location.pathname === '/'
  const { query } = parseCatalogParams(new URLSearchParams(onCatalog ? location.search : ''))
  const [text, setText] = useState(query.q ?? '')

  // Follow the URL when it changes underneath us (Back button, a cleared filter chip).
  useEffect(() => {
    setText(query.q ?? '')
  }, [query.q])

  const search = (q: string) => {
    navigate({ pathname: '/', search: toSearchString({ ...(onCatalog ? query : {}), q: q.trim() || undefined }) })
  }

  return (
    <form
      role="search"
      onSubmit={(e) => {
        e.preventDefault()
        search(text)
      }}
      className="group flex w-full items-stretch overflow-hidden rounded-full border-2 border-ink bg-surface shadow-[3px_3px_0_hsl(var(--color-ink))] transition-all duration-200 focus-within:-translate-y-0.5 focus-within:shadow-[5px_5px_0_hsl(var(--color-ink))]"
    >
      <label htmlFor="site-search" className="sr-only">
        Search Cartwise
      </label>
      <input
        id="site-search"
        type="search"
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="Search the catalog of tomorrow…"
        autoComplete="off"
        className="min-w-0 flex-1 bg-transparent px-5 py-2.5 text-sm text-on-surface placeholder:text-on-surface-muted focus:outline-none [&::-webkit-search-cancel-button]:hidden"
      />
      {text && (
        <button
          type="button"
          onClick={() => {
            setText('')
            if (query.q) search('')
          }}
          className="px-2 text-on-surface-muted hover:text-on-surface"
          aria-label="Clear search"
        >
          <CloseIcon className="w-4 h-4" />
        </button>
      )}
      <button
        type="submit"
        className="press shine bg-cta-gradient flex items-center border-l-2 border-ink px-5 text-on-accent"
        aria-label="Search"
      >
        <SearchIcon className="w-5 h-5 transition-transform duration-300 group-focus-within:scale-110" />
      </button>
    </form>
  )
}
