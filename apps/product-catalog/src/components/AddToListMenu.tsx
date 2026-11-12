/**
 * "Add to list" menu on the product page: choose an existing list or create one. Lazy-loaded.
 */

import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { secondaryButtonClass } from './Form'
import { errorMessage } from '../lib/errors'
import { effectivePrice } from '../lib/price'
import { engageApi } from '../services/engageApi'
import { useAuthStore, useUiStore } from '../stores'
import type { Product, ProductList } from '../types'

interface Props {
  product: Product
  variantId?: string
  variantImage?: string | null
}

export default function AddToListMenu({ product, variantId, variantImage }: Props) {
  const authenticated = useAuthStore((s) => s.status === 'authenticated')
  const notify = useUiStore((s) => s.notify)
  const { pathname } = useLocation()
  const [open, setOpen] = useState(false)
  const [lists, setLists] = useState<ProductList[] | null>(null)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)

  if (!authenticated) {
    return (
      <Link to={`/login?next=${encodeURIComponent(pathname)}`} className={secondaryButtonClass}>
        Sign in to add to a list
      </Link>
    )
  }

  const toggle = async () => {
    const next = !open
    setOpen(next)
    if (next && !lists) {
      try {
        setLists(await engageApi.lists())
      } catch (err) {
        notify(errorMessage(err))
        setOpen(false)
      }
    }
  }

  const add = async (list: ProductList) => {
    setBusy(true)
    try {
      await engageApi.addListItem(list.id, {
        productId: product.id,
        ...(variantId ? { variantId } : {}),
        name: product.name,
        price: effectivePrice(product),
        currency: product.priceCurrency,
        ...((variantImage ?? product.images[0]) ? { imageUrl: (variantImage ?? product.images[0]) as string } : {}),
      })
      notify(`Added to ${list.name}`)
      setOpen(false)
    } catch (err) {
      notify(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const create = async (e: React.FormEvent) => {
    e.preventDefault()
    const n = name.trim()
    if (!n) return
    setBusy(true)
    try {
      const list = await engageApi.createList(n)
      setName('')
      setLists((prev) => [...(prev ?? []), list])
      await add(list)
    } catch (err) {
      notify(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <div className="relative">
      <button type="button" aria-haspopup="true" aria-expanded={open} className={secondaryButtonClass} onClick={() => void toggle()}>
        Add to list
      </button>
      {open && (
        <div className="absolute left-0 top-full z-30 mt-2 w-64 space-y-2 rounded-2xl border-2 border-ink bg-surface p-3 shadow-card-hover">
          {!lists ? (
            <p className="text-sm text-on-surface-muted">Loading...</p>
          ) : (
            <ul className="max-h-48 overflow-y-auto">
              {lists.map((l) => (
                <li key={l.id}>
                  <button type="button" disabled={busy} className="w-full rounded-lg px-2 py-1.5 text-left text-sm text-on-surface hover:bg-surface-variant" onClick={() => void add(l)}>
                    {l.name}
                  </button>
                </li>
              ))}
            </ul>
          )}
          <form onSubmit={(e) => void create(e)} className="flex gap-2">
            <input aria-label="New list name" maxLength={60} placeholder="New list" value={name} onChange={(e) => setName(e.target.value)} className="min-w-0 flex-1 rounded-lg border-2 border-ink bg-surface px-2 py-1 text-sm" />
            <button type="submit" disabled={busy || !name.trim()} className="rounded-lg border-2 border-ink bg-vivid-amber px-2 text-sm font-bold text-ink disabled:opacity-60">
              Add
            </button>
          </form>
        </div>
      )}
    </div>
  )
}
