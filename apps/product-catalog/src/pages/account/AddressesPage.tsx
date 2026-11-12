/**
 * Saved addresses: list, add, edit, delete and choose the default
 */

import { useCallback, useState } from 'react'
import { AddressForm, formatAddressLines } from '../../components/AddressForm'
import { ErrorAlert } from '../../components/ErrorAlert'
import { CardSkeleton } from '../../components/account/SectionCard'
import { cardClass, primaryButtonClass, secondaryButtonClass } from '../../components/Form'
import { MapPinIcon, PhoneIcon } from '../../components/Icons'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { userApi } from '../../services/userApi'
import { useUiStore } from '../../stores'
import type { AddressRequest } from '../../types'

type Mode = { kind: 'list' } | { kind: 'new' } | { kind: 'edit'; id: string }

export function AddressesPage() {
  const load = useCallback(() => userApi.listAddresses(), [])
  const { data: addresses, error, loading, reload } = useLoad(load)
  const notify = useUiStore((s) => s.notify)
  const [mode, setMode] = useState<Mode>({ kind: 'list' })
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

  if (loading && !addresses) return <CardSkeleton rows={3} />
  if (error) return <ErrorAlert message={errorMessage(error)} />

  const editing = mode.kind === 'edit' ? addresses?.find((a) => a.id === mode.id) : undefined

  const create = async (value: AddressRequest) => {
    await userApi.createAddress(value)
    notify('Address added')
    setMode({ kind: 'list' })
    reload()
  }

  const update = async (id: string, value: AddressRequest) => {
    await userApi.updateAddress(id, value)
    notify('Address updated')
    setMode({ kind: 'list' })
    reload()
  }

  return (
    <div className="space-y-4">
      {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}

      {mode.kind === 'new' && (
        <div className={cardClass}>
          <h2 className="mb-4 font-display text-lg font-bold text-on-surface">New address</h2>
          <AddressForm onSubmit={create} onCancel={() => setMode({ kind: 'list' })} />
        </div>
      )}

      {mode.kind === 'edit' && editing && (
        <div className={cardClass}>
          <h2 className="mb-4 font-display text-lg font-bold text-on-surface">Edit address</h2>
          <AddressForm initial={editing} onSubmit={(v) => update(editing.id, v)} onCancel={() => setMode({ kind: 'list' })} />
        </div>
      )}

      {mode.kind === 'list' && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="font-display text-2xl text-on-surface">Delivery addresses</h2>
            <p className="text-sm text-on-surface-muted">
              {addresses?.length
                ? `${addresses.length} saved ${addresses.length === 1 ? 'address' : 'addresses'}. Your default is used first at checkout.`
                : 'Save an address to check out faster.'}
            </p>
          </div>
          <button type="button" onClick={() => setMode({ kind: 'new' })} className={primaryButtonClass}>
            Add address
          </button>
        </div>
      )}

      {addresses?.length === 0 && mode.kind === 'list' && (
        <div className={`${cardClass} flex flex-col items-center gap-3 py-12 text-center`}>
          <MapPinIcon className="h-12 w-12 text-on-surface-muted" />
          <p className="font-bold text-on-surface">No saved addresses yet</p>
          <p className="max-w-sm text-sm text-on-surface-muted">Add where you want your orders delivered and we will remember it.</p>
        </div>
      )}

      <ul className="grid gap-4 md:grid-cols-2">
        {addresses?.map((a) => (
          <li
            key={a.id}
            className={`${cardClass} relative space-y-4 transition-all duration-700 hover:-translate-y-0.5 hover:shadow-card-hover ${
              a.isDefault ? '!border-ink !bg-vivid-amber/10' : ''
            }`}
          >
            <div className="flex items-center gap-3">
              <span
                className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-full border-2 border-ink text-ink ${
                  a.isDefault ? 'bg-vivid-amber' : 'bg-surface-variant'
                }`}
                aria-hidden="true"
              >
                <MapPinIcon className="h-5 w-5" />
              </span>
              <span className="font-display min-w-0 flex-1 truncate text-lg text-on-surface">{a.label || 'Address'}</span>
              {a.isDefault && (
                <span className="rounded-full border-2 border-ink bg-vivid-amber px-2.5 py-0.5 text-xs font-bold text-ink">Default</span>
              )}
            </div>
            <address className="space-y-0.5 text-sm not-italic text-on-surface">
              {formatAddressLines(a).map((line, i) => (
                <div key={i} className={i === 0 ? 'font-bold' : undefined}>
                  {line}
                </div>
              ))}
              {a.phone && (
                <div className="flex items-center gap-1.5 pt-1 text-on-surface-muted">
                  <PhoneIcon className="h-3.5 w-3.5" />
                  {a.phone}
                </div>
              )}
            </address>
            <div className="flex flex-wrap gap-2">
              <button type="button" className={secondaryButtonClass} onClick={() => setMode({ kind: 'edit', id: a.id })}>
                Edit
              </button>
              {!a.isDefault && (
                <button
                  type="button"
                  className={secondaryButtonClass}
                  onClick={() => void run(() => userApi.setDefaultAddress(a.id), 'Default address updated')}
                >
                  Make default
                </button>
              )}
              <button
                type="button"
                className={`${secondaryButtonClass} text-error`}
                onClick={() => {
                  if (window.confirm('Delete this address?')) void run(() => userApi.deleteAddress(a.id), 'Address deleted')
                }}
              >
                Delete
              </button>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}
