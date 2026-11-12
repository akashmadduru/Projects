/**
 * Create/edit form for a shipping address
 */

import { useState } from 'react'
import { ErrorAlert } from './ErrorAlert'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from './Form'
import { errorMessage } from '../lib/errors'
import type { AddressRequest } from '../types'

interface Props {
  initial?: AddressRequest
  submitLabel?: string
  onSubmit: (value: AddressRequest) => Promise<void>
  onCancel?: () => void
}

const EMPTY: AddressRequest = {
  label: null,
  fullName: '',
  phone: null,
  line1: '',
  line2: null,
  city: '',
  state: null,
  postalCode: '',
  country: '',
}

const blankToNull = (value: string | null): string | null => (value && value.trim() ? value.trim() : null)

export function AddressForm({ initial, submitLabel = 'Save address', onSubmit, onCancel }: Props) {
  const [form, setForm] = useState<AddressRequest>(initial ?? EMPTY)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const set = <K extends keyof AddressRequest>(key: K, value: string) =>
    setForm((f) => ({ ...f, [key]: value }))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setError(undefined)
    try {
      await onSubmit({
        label: blankToNull(form.label),
        fullName: form.fullName.trim(),
        phone: blankToNull(form.phone),
        line1: form.line1.trim(),
        line2: blankToNull(form.line2),
        city: form.city.trim(),
        state: blankToNull(form.state),
        postalCode: form.postalCode.trim(),
        country: form.country.trim().toUpperCase(),
      })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={(e) => void handleSubmit(e)} className="space-y-4">
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Label (optional)">
          {(id) => (
            <input id={id} className={inputClass} maxLength={50} placeholder="Home, Work..." value={form.label ?? ''} onChange={(e) => set('label', e.target.value)} />
          )}
        </Field>
        <Field label="Full name">
          {(id) => (
            <input id={id} className={inputClass} required autoComplete="name" value={form.fullName} onChange={(e) => set('fullName', e.target.value)} />
          )}
        </Field>
        <Field label="Phone (optional)">
          {(id) => (
            <input id={id} type="tel" className={inputClass} autoComplete="tel" value={form.phone ?? ''} onChange={(e) => set('phone', e.target.value)} />
          )}
        </Field>
        <Field label="Country (2-letter code)">
          {(id) => (
            <input id={id} className={inputClass} required minLength={2} maxLength={2} placeholder="US" autoComplete="country" value={form.country} onChange={(e) => set('country', e.target.value)} />
          )}
        </Field>
      </div>
      <Field label="Address line 1">
        {(id) => (
          <input id={id} className={inputClass} required autoComplete="address-line1" value={form.line1} onChange={(e) => set('line1', e.target.value)} />
        )}
      </Field>
      <Field label="Address line 2 (optional)">
        {(id) => (
          <input id={id} className={inputClass} autoComplete="address-line2" value={form.line2 ?? ''} onChange={(e) => set('line2', e.target.value)} />
        )}
      </Field>
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="City">
          {(id) => (
            <input id={id} className={inputClass} required autoComplete="address-level2" value={form.city} onChange={(e) => set('city', e.target.value)} />
          )}
        </Field>
        <Field label="State / region">
          {(id) => (
            <input id={id} className={inputClass} autoComplete="address-level1" value={form.state ?? ''} onChange={(e) => set('state', e.target.value)} />
          )}
        </Field>
        <Field label="Postal code">
          {(id) => (
            <input id={id} className={inputClass} required autoComplete="postal-code" value={form.postalCode} onChange={(e) => set('postalCode', e.target.value)} />
          )}
        </Field>
      </div>
      <div className="flex gap-3">
        <button type="submit" disabled={submitting} className={primaryButtonClass}>
          {submitting ? 'Saving...' : submitLabel}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} disabled={submitting} className={secondaryButtonClass}>
            Cancel
          </button>
        )}
      </div>
    </form>
  )
}

export function formatAddressLines(a: {
  fullName: string
  line1: string
  line2: string | null
  city: string
  state: string | null
  postalCode: string
  country: string
}): string[] {
  return [
    a.fullName,
    a.line1,
    ...(a.line2 ? [a.line2] : []),
    [a.city, a.state, a.postalCode].filter(Boolean).join(', '),
    a.country,
  ]
}
