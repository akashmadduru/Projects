/**
 * Header product search with autocomplete (ARIA combobox + listbox). Debounced 200 ms suggestions,
 * keyboard navigation, recent searches and a "did you mean" hint. Submitting navigates to the catalog with ?q=.
 */

import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { CloseIcon, SearchIcon } from './Icons'
import { parseCatalogParams, toSearchString } from '../hooks'
import { formatMoney } from '../lib/format'
import { productPath } from '../lib/productPath'
import { addRecentSearch, clearRecentSearches, getRecentSearches } from '../lib/recentSearches'
import { discoveryApi } from '../services/discoveryApi'
import type { SuggestResponse } from '../types'

const DEBOUNCE_MS = 200
const MIN_CHARS = 2

type Option =
  | { kind: 'recent'; key: string; label: string }
  | { kind: 'product'; key: string; label: string; path: string; price: string; image: string | null }
  | { kind: 'category'; key: string; label: string; id: string }
  | { kind: 'didYouMean'; key: string; label: string }

export function SearchBar() {
  const navigate = useNavigate()
  const location = useLocation()
  const onCatalog = location.pathname === '/'
  const { query } = parseCatalogParams(new URLSearchParams(onCatalog ? location.search : ''))
  const [text, setText] = useState(query.q ?? '')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const [suggest, setSuggest] = useState<SuggestResponse | undefined>()
  const [recent, setRecent] = useState<string[]>([])
  const listId = useId()
  const rootRef = useRef<HTMLDivElement>(null)

  // Follow the URL when it changes underneath us (Back button, a cleared filter chip).
  useEffect(() => {
    setText(query.q ?? '')
  }, [query.q])

  const trimmed = text.trim()
  useEffect(() => {
    if (trimmed.length < MIN_CHARS) {
      setSuggest(undefined)
      return
    }
    let live = true
    const timer = setTimeout(() => {
      discoveryApi
        .suggest(trimmed)
        .then((res) => {
          if (live) setSuggest(res)
        })
        .catch(() => {
          if (live) setSuggest(undefined)
        })
    }, DEBOUNCE_MS)
    return () => {
      live = false
      clearTimeout(timer)
    }
  }, [trimmed])

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  const options: Option[] = useMemo(() => {
    if (trimmed.length < MIN_CHARS) return recent.map((r) => ({ kind: 'recent', key: `r-${r}`, label: r }))
    if (!suggest || suggest.query !== trimmed) return []
    const list: Option[] = []
    if (suggest.didYouMean) list.push({ kind: 'didYouMean', key: 'dym', label: suggest.didYouMean })
    for (const c of suggest.categories) list.push({ kind: 'category', key: `c-${c.id}`, label: c.name, id: c.id })
    for (const p of suggest.products) {
      list.push({
        kind: 'product',
        key: `p-${p.id}`,
        label: p.name,
        path: productPath(p),
        price: formatMoney(p.salePrice && p.salePrice > 0 ? p.salePrice : p.priceAmount, p.priceCurrency),
        image: p.thumbnailUrl,
      })
    }
    return list
  }, [trimmed, suggest, recent])

  const search = (q: string) => {
    const term = q.trim()
    addRecentSearch(term)
    setOpen(false)
    navigate({ pathname: '/', search: toSearchString({ ...(onCatalog ? query : {}), q: term || undefined }) })
  }

  const choose = (option: Option) => {
    if (option.kind === 'product') {
      addRecentSearch(trimmed)
      setOpen(false)
      navigate(option.path)
    } else if (option.kind === 'category') {
      setOpen(false)
      navigate({ pathname: '/', search: toSearchString({ categoryId: option.id }) })
    } else {
      setText(option.label)
      search(option.label)
    }
  }

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setOpen(true)
      setActive((i) => (options.length ? (i + 1) % options.length : -1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => (options.length ? (i <= 0 ? options.length - 1 : i - 1) : -1))
    } else if (e.key === 'Escape') {
      setOpen(false)
      setActive(-1)
    } else if (e.key === 'Enter' && open && active >= 0 && options[active]) {
      e.preventDefault()
      choose(options[active])
    }
  }

  const expanded = open && options.length > 0

  return (
    <div ref={rootRef} className="relative w-full">
      <form
        role="search"
        onSubmit={(e) => {
          e.preventDefault()
          search(text)
        }}
        className="group flex w-full items-stretch overflow-hidden rounded-full border-2 border-ink bg-surface shadow-[3px_3px_0_hsl(var(--color-ink))] transition-all duration-500 focus-within:-translate-y-0.5 focus-within:shadow-[5px_5px_0_hsl(var(--color-ink))]"
      >
        <label htmlFor="site-search" className="sr-only">
          Search Cartwise
        </label>
        <input
          id="site-search"
          type="search"
          role="combobox"
          aria-expanded={expanded}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={expanded && active >= 0 ? `${listId}-${active}` : undefined}
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            setActive(-1)
            setOpen(true)
          }}
          onFocus={() => {
            setRecent(getRecentSearches())
            setOpen(true)
          }}
          onKeyDown={onKeyDown}
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
          <SearchIcon className="w-5 h-5 transition-transform duration-700 group-focus-within:scale-110" />
        </button>
      </form>

      <ul
        id={listId}
        role="listbox"
        aria-label="Search suggestions"
        hidden={!expanded}
        className="absolute left-0 right-0 top-full z-50 mt-2 max-h-[70vh] overflow-y-auto rounded-2xl border-2 border-ink bg-surface p-1.5 shadow-card-hover"
      >
        {expanded && trimmed.length < MIN_CHARS && (
          <li role="presentation" className="flex items-center justify-between px-3 py-1 text-xs font-bold uppercase tracking-[0.18em] text-accent-2">
            Recent searches
            <button
              type="button"
              className="normal-case tracking-normal text-on-surface-muted hover:text-error"
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => {
                clearRecentSearches()
                setRecent([])
              }}
            >
              Clear
            </button>
          </li>
        )}
        {options.map((option, i) => (
          <li
            key={option.key}
            id={`${listId}-${i}`}
            role="option"
            aria-selected={i === active}
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => choose(option)}
            onMouseEnter={() => setActive(i)}
            className={`flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2 text-sm ${
              i === active ? 'bg-vivid-amber/30' : ''
            }`}
          >
            {option.kind === 'product' && (
              <>
                {option.image ? (
                  <img src={option.image} alt="" className="h-9 w-9 shrink-0 rounded-lg border border-outline object-cover" />
                ) : (
                  <span className="h-9 w-9 shrink-0 rounded-lg bg-surface-variant" aria-hidden="true" />
                )}
                <span className="min-w-0 flex-1 truncate font-medium text-on-surface">{option.label}</span>
                <span className="shrink-0 font-bold text-on-surface">{option.price}</span>
              </>
            )}
            {option.kind === 'category' && (
              <span className="text-on-surface">
                in <span className="font-bold text-primary">{option.label}</span>
              </span>
            )}
            {option.kind === 'recent' && <span className="text-on-surface">{option.label}</span>}
            {option.kind === 'didYouMean' && (
              <span className="text-on-surface">
                Did you mean <span className="font-bold text-primary">{option.label}</span>?
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
