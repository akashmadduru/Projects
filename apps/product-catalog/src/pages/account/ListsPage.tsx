/**
 * Account: multiple lists - create, rename, delete, share by link, remove items and move them between lists.
 */

import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { ErrorAlert } from '../../components/ErrorAlert'
import { cardClass, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/Form'
import { PageShell } from '../../components/account/PageShell'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { engageApi } from '../../services/engageApi'
import { useUiStore } from '../../stores'
import type { ListItem, ProductList } from '../../types'

export function ListsPage() {
  const notify = useUiStore((s) => s.notify)
  const load = useCallback(() => engageApi.lists(), [])
  const { data, loading, error, reload } = useLoad(load)
  const [name, setName] = useState('')
  const [actionError, setActionError] = useState<string | undefined>()

  const run = async (action: () => Promise<unknown>, done?: string) => {
    setActionError(undefined)
    try {
      await action()
      if (done) notify(done)
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    }
  }

  const share = (list: ProductList) =>
    run(async () => {
      const { shareUrl } = await engageApi.shareList(list.id)
      try {
        await navigator.clipboard.writeText(shareUrl)
        notify('Share link copied')
      } catch {
        notify(`Share link: ${shareUrl}`)
      }
    })

  const move = (from: ProductList, item: ListItem, toId: string) =>
    run(async () => {
      await engageApi.addListItem(toId, {
        productId: item.productId,
        ...(item.variantId ? { variantId: item.variantId } : {}),
        ...(item.note ? { note: item.note } : {}),
        name: item.name,
        price: item.price,
        currency: item.currency,
        ...(item.imageUrl ? { imageUrl: item.imageUrl } : {}),
      })
      await engageApi.removeListItem(from.id, item.productId, item.variantId)
    }, 'Moved')

  return (
    <PageShell id="lists" title="Lists" intro="Organise products into lists and share them with a link." loading={loading && !data} error={error}>
      {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}
      <form
        className={`${cardClass} flex flex-wrap gap-2`}
        onSubmit={(e) => {
          e.preventDefault()
          const n = name.trim()
          if (!n) return
          void run(() => engageApi.createList(n), 'List created').then(() => setName(''))
        }}
      >
        <input aria-label="New list name" className={`${inputClass} min-w-0 flex-1`} maxLength={60} placeholder="New list name" value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit" className={primaryButtonClass} disabled={!name.trim()}>
          Create list
        </button>
      </form>

      {(data ?? []).map((list) => (
        <section key={list.id} aria-label={list.name} className={`${cardClass} space-y-3`}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="font-bold text-on-surface">
              {list.name}
              {list.isDefault && <span className="ml-2 text-xs font-normal text-on-surface-muted">(default)</span>}
              {list.visibility === 'SHARED' && <span className="ml-2 rounded-full border-2 border-ink bg-vivid-amber/40 px-2 text-xs">Shared</span>}
            </h3>
            <div className="flex flex-wrap gap-2">
              {!list.isDefault && (
                <button
                  type="button"
                  className={secondaryButtonClass}
                  onClick={() => {
                    const n = window.prompt('Rename list', list.name)?.trim()
                    if (n) void run(() => engageApi.renameList(list.id, n.slice(0, 60)))
                  }}
                >
                  Rename
                </button>
              )}
              <button type="button" className={secondaryButtonClass} onClick={() => void share(list)}>
                {list.visibility === 'SHARED' ? 'Copy link' : 'Share'}
              </button>
              {list.visibility === 'SHARED' && (
                <button type="button" className={secondaryButtonClass} onClick={() => void run(() => engageApi.unshareList(list.id), 'Sharing stopped')}>
                  Stop sharing
                </button>
              )}
              {!list.isDefault && (
                <button
                  type="button"
                  className={`${secondaryButtonClass} !text-error`}
                  onClick={() => {
                    if (window.confirm(`Delete "${list.name}"?`)) void run(() => engageApi.deleteList(list.id), 'List deleted')
                  }}
                >
                  Delete
                </button>
              )}
            </div>
          </div>
          {(list.items ?? []).length === 0 ? (
            <p className="text-sm text-on-surface-muted">{list.itemCount ? `${list.itemCount} items` : 'No items yet.'}</p>
          ) : (
            <ul className="divide-y divide-outline">
              {(list.items ?? []).map((item) => (
                <li key={`${item.productId}:${item.variantId ?? ''}`} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
                  <div className="min-w-0">
                    <Link to={`/products/${item.productId}`} className="font-semibold text-on-surface hover:text-primary">
                      {item.name}
                    </Link>
                    <span className="ml-2 text-on-surface-muted">{formatMoney(item.price, item.currency)}</span>
                    {item.note && <p className="text-xs text-on-surface-muted">{item.note}</p>}
                  </div>
                  <div className="flex items-center gap-2">
                    {(data ?? []).length > 1 && (
                      <select
                        aria-label={`Move ${item.name} to another list`}
                        className="rounded-lg border-2 border-ink bg-surface px-2 py-1 text-xs"
                        value=""
                        onChange={(e) => e.target.value && void move(list, item, e.target.value)}
                      >
                        <option value="">Move to...</option>
                        {(data ?? []).filter((l) => l.id !== list.id).map((l) => (
                          <option key={l.id} value={l.id}>
                            {l.name}
                          </option>
                        ))}
                      </select>
                    )}
                    <button type="button" className={`${secondaryButtonClass} !text-error`} aria-label={`Remove ${item.name}`} onClick={() => void run(() => engageApi.removeListItem(list.id, item.productId, item.variantId))}>
                      Remove
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      ))}
    </PageShell>
  )
}
