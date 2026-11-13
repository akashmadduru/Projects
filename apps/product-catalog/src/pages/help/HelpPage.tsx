/** Help centre: search + category filter over the public FAQ articles. */

import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ErrorAlert } from '../../components/ErrorAlert'
import { LoadingBlock, cardClass, inputClass, secondaryButtonClass } from '../../components/Form'
import { useHead } from '../../hooks/useHead'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { useT } from '../../lib/i18n'
import { supportApi } from '../../services/supportApi'
import { useAuthStore } from '../../stores'
import type { FaqCategory } from '../../types'

const CATEGORIES: { key: FaqCategory; label: string }[] = [
  { key: 'ORDERS', label: 'Orders & delivery' },
  { key: 'PAYMENTS', label: 'Payments' },
  { key: 'RETURNS', label: 'Returns & refunds' },
  { key: 'ACCOUNT', label: 'Account' },
  { key: 'SELLERS', label: 'Selling' },
  { key: 'OTHER', label: 'Other' },
]

export function HelpPage() {
  const t = useT()
  useHead({ title: `${t('Help centre')} | Cartwise` })
  const [params, setParams] = useSearchParams()
  const category = (params.get('category') as FaqCategory | null) ?? undefined
  const urlQuery = params.get('q') ?? ''
  const [query, setQuery] = useState(urlQuery)
  const authenticated = useAuthStore((s) => s.status === 'authenticated')

  useEffect(() => {
    const id = setTimeout(() => {
      setParams((prev) => {
        const next = new URLSearchParams(prev)
        if (query.trim()) next.set('q', query.trim())
        else next.delete('q')
        return next
      }, { replace: true })
    }, 300)
    return () => clearTimeout(id)
  }, [query, setParams])

  const load = useCallback(() => supportApi.articles({ category, q: urlQuery || undefined }), [category, urlQuery])
  const { data, loading, error } = useLoad(load)

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <header className="space-y-3">
        <h1 className="font-display text-3xl font-bold text-on-surface">{t('How can we help?')}</h1>
        <label htmlFor="help-search" className="sr-only">
          {t('Search help articles')}
        </label>
        <input
          id="help-search"
          type="search"
          className={inputClass}
          placeholder={t('Search help articles')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <div className="flex flex-wrap gap-2" role="group" aria-label="Categories">
          <button
            type="button"
            aria-pressed={!category}
            className={secondaryButtonClass}
            onClick={() => setParams((p) => { const n = new URLSearchParams(p); n.delete('category'); return n })}
          >
            All
          </button>
          {CATEGORIES.map((c) => (
            <button
              key={c.key}
              type="button"
              aria-pressed={category === c.key}
              className={`${secondaryButtonClass} ${category === c.key ? '!bg-primary-container' : ''}`}
              onClick={() => setParams((p) => { const n = new URLSearchParams(p); n.set('category', c.key); return n })}
            >
              {c.label}
            </button>
          ))}
        </div>
      </header>

      {loading && !data ? (
        <LoadingBlock label="Loading articles" />
      ) : error ? (
        <ErrorAlert message={errorMessage(error)} />
      ) : data && data.length > 0 ? (
        <ul className="grid gap-3 sm:grid-cols-2">
          {data.map((a) => (
            <li key={a.id}>
              <Link to={`/help/${a.slug}`} className={`${cardClass} block h-full hover:bg-surface-variant`}>
                <span className="text-xs font-bold uppercase tracking-wider text-on-surface-muted">{a.category}</span>
                <span className="mt-1 block font-bold text-on-surface">{a.title}</span>
              </Link>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-on-surface-muted" role="status">
          No articles matched. Try different words, or contact support.
        </p>
      )}

      <div className={`${cardClass} flex flex-wrap items-center justify-between gap-3`}>
        <p className="font-semibold text-on-surface">Still stuck?</p>
        <Link to={authenticated ? '/account/support/new' : '/login?next=%2Faccount%2Fsupport%2Fnew'} className={secondaryButtonClass}>
          {t('Contact support')}
        </Link>
      </div>
    </div>
  )
}

export default HelpPage
