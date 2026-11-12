/**
 * Saved addresses: list, add, edit (in place), delete (inline confirm) and choose the default.
 * The default address is always shown first.
 */

import { useCallback, useMemo, useState } from 'react'
import { AddressForm, formatAddressLines } from '../../components/AddressForm'
import { ErrorAlert } from '../../components/ErrorAlert'
import { CardSkeleton } from '../../components/account/SectionCard'
import { cardClass, primaryButtonClass, secondaryButtonClass } from '../../components/Form'
import { MapPinIcon, PhoneIcon, PlusIcon } from '../../components/Icons'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { userApi } from '../../services/userApi'
import { useUiStore } from '../../stores'
import type { AddressRequest } from '../../types'

type Mode = { kind: 'list' } | { kind: 'new' } | { kind: 'edit'; id: string }

export function AddressesPage() {
  const load = useCallback(() => userApi.listAddresses(), [])
  const { data, error, loading, reload } = useLoad(load)
  const notify = useUiStore((s) => s.notify)
  const [mode, setMode] = useState<Mode>({ kind: 'list' })
  const [actionError, setActionError] = useState<string | undefined>()
  const [busyId, setBusyId] = useState<string | null>(null)
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null)

  const addresses = useMemo(
    () => [...(data ?? [])].sort((a, b) => Number(b.isDefault) - Number(a.isDefault)),
    [data],
  )

  const run = async (id: string, action: () => Promise<unknown>, done: string) => {
    setActionError(undefined)
    setBusyId(id)
    try {
      await action()
      notify(done)
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusyId(null)
      setConfirmDeleteId(null)
    }
  }

  if (loading && !data) return <CardSkeleton rows={3} />
  if (error) return <ErrorAlert message={errorMessage(error)} />

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

  const startNew = () => {
    setConfirmDeleteId(null)
    setMode({ kind: 'new' })
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="font-display text-2xl text-on-surface">Delivery addresses</h2>
          <p className="text-sm text-on-surface-muted">
            {addresses.length
              ? `${addresses.length} saved ${addresses.length === 1 ? 'address' : 'addresses'}. Your default is used first at checkout.`
              : 'Save an address to check out faster.'}
          </p>
        </div>
        {mode.kind !== 'new' && (
          <button type="button" onClick={startNew} className={primaryButtonClass}>
            <PlusIcon className="h-4 w-4" /> Add address
          </button>
        )}
      </div>

      {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}

      {mode.kind === 'new' && (
        <div className={`${cardClass} animate-slide-up !border-ink`}>
          <h3 className="mb-4 font-display text-lg font-bold text-on-surface">New address</h3>
          <AddressForm onSubmit={create} onCancel={() => setMode({ kind: 'list' })} />
        </div>
      )}

      {addresses.length === 0 && mode.kind === 'list' && (
        <div className={`${cardClass} flex flex-col items-center gap-3 py-12 text-center`}>
          <MapPinIcon className="h-12 w-12 text-on-surface-muted" />
          <p className="font-bold text-on-surface">No saved addresses yet</p>
          <p className="max-w-sm text-sm text-on-surface-muted">Add where you want your orders delivered and we will remember it.</p>
          <button type="button" onClick={startNew} className={`${primaryButtonClass} mt-1`}>
            <PlusIcon className="h-4 w-4" /> Add your first address
          </button>
        </div>
      )}

      <ul className="grid gap-4 md:grid-cols-2">
        {addresses.map((a) => {
          if (mode.kind === 'edit' && mode.id === a.id) {
            return (
              <li key={a.id} className={`${cardClass} animate-slide-up !border-ink md:col-span-2`}>
                <h3 className="mb-4 font-display text-lg font-bold text-on-surface">Edit “{a.label || 'Address'}”</h3>
                <AddressForm initial={a} onSubmit={(v) => update(a.id, v)} onCancel={() => setMode({ kind: 'list' })} />
              </li>
            )
          }
          const busy = busyId === a.id
          const confirming = confirmDeleteId === a.id
          return (
            <li
              key={a.id}
              className={`${cardClass} relative flex flex-col gap-4 transition-all duration-700 hover:-translate-y-0.5 hover:shadow-card-hover ${
                a.isDefault ? '!border-ink !bg-vivid-amber/10' : ''
              } ${busy ? 'opacity-60' : ''}`}
              aria-busy={busy}
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
                  <span className="rounded-full border-2 border-ink bg-vivid-amber px-2.5 py-0.5 text-xs font-bold text-ink">
                    Default
                  </span>
                )}
              </div>
              <address className="flex-1 space-y-0.5 text-sm not-italic text-on-surface">
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

              {confirming ? (
                <div
                  className="flex flex-wrap items-center gap-2 rounded-2xl border-2 border-error/50 bg-error/10 p-3"
                  role="group"
                  aria-label="Confirm delete"
                >
                  <span className="flex-1 text-sm font-bold text-error">
                    Delete this address?{a.isDefault ? ' You will need to pick a new default.' : ''}
                  </span>
                  <button
                    type="button"
                    className={`${secondaryButtonClass} !border-error !text-error`}
                    disabled={busy}
                    onClick={() => void run(a.id, () => userApi.deleteAddress(a.id), 'Address deleted')}
                  >
                    Delete
                  </button>
                  <button type="button" className={secondaryButtonClass} disabled={busy} onClick={() => setConfirmDeleteId(null)}>
                    Keep
                  </button>
                </div>
              ) : (
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    className={secondaryButtonClass}
                    disabled={busy || busyId !== null}
                    onClick={() => setMode({ kind: 'edit', id: a.id })}
                  >
                    Edit
                  </button>
                  {!a.isDefault && (
                    <button
                      type="button"
                      className={secondaryButtonClass}
                      disabled={busy || busyId !== null}
                      onClick={() => void run(a.id, () => userApi.setDefaultAddress(a.id), 'Default address updated')}
                    >
                      Make default
                    </button>
                  )}
                  <button
                    type="button"
                    className={`${secondaryButtonClass} sm:ml-auto !text-error`}
                    disabled={busy || busyId !== null}
                    onClick={() => setConfirmDeleteId(a.id)}
                  >
                    Delete
                  </button>
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
