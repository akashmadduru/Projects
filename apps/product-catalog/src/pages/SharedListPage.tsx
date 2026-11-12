/**
 * Public page for a list shared by link (no sign-in needed).
 */

import { useCallback } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ErrorAlert } from '../components/ErrorAlert'
import { LoadingBlock, cardClass } from '../components/Form'
import { useHead } from '../hooks/useHead'
import { useLoad } from '../hooks/useLoad'
import { errorMessage } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { engageApi } from '../services/engageApi'

export function SharedListPage() {
  const { token = '' } = useParams()
  const load = useCallback(() => engageApi.sharedList(token), [token])
  const { data, loading, error } = useLoad(load)
  useHead({ title: data ? `${data.name} - shared list` : 'Shared list' })

  if (loading && !data) return <LoadingBlock label="Loading list" />
  if (error) return <ErrorAlert message={errorMessage(error)} />
  if (!data) return null

  return (
    <section aria-labelledby="shared-title" className="space-y-4">
      <div className={`${cardClass} space-y-1`}>
        <h1 id="shared-title" className="font-display text-2xl font-extrabold text-on-surface">
          {data.name}
        </h1>
        <p className="text-sm text-on-surface-muted">Shared by {data.ownerFirstName}</p>
      </div>
      {data.items.length === 0 ? (
        <p className="text-sm text-on-surface-muted">This list is empty.</p>
      ) : (
        <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {data.items.map((item) => (
            <li key={`${item.productId}:${item.variantId ?? ''}`}>
              <Link to={`/products/${item.productId}`} className={`${cardClass} flex items-center gap-3 hover:shadow-card-hover`}>
                {item.imageUrl && <img src={item.imageUrl} alt="" className="h-16 w-16 rounded-lg object-cover" loading="lazy" />}
                <span className="min-w-0">
                  <span className="line-clamp-2 block font-semibold text-on-surface">{item.name}</span>
                  <span className="block text-sm text-on-surface-muted">{formatMoney(item.price, item.currency)}</span>
                  {item.note && <span className="block text-xs text-on-surface-muted">{item.note}</span>}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
