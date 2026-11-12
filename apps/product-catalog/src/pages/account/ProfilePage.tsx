/**
 * Personal info: edit display name and phone with a live preview, validation and
 * unsaved-change tracking
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Avatar } from '../../components/account/Avatar'
import { CompletenessRing } from '../../components/account/CompletenessRing'
import { CardSkeleton, SectionCard } from '../../components/account/SectionCard'
import { ErrorAlert } from '../../components/ErrorAlert'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/Form'
import { CalendarIcon, CheckBadgeIcon, CopyIcon, LockIcon, MailIcon, PhoneIcon, UserIcon } from '../../components/Icons'
import { useLoad } from '../../hooks/useLoad'
import { DISPLAY_NAME_MAX, PHONE_MAX, displayNameOf, profileCompleteness, validatePhone } from '../../lib/account'
import { errorMessage } from '../../lib/errors'
import { userApi } from '../../services/userApi'
import { useAuthStore, useUiStore } from '../../stores'

function Counter({ value, max }: { value: number; max: number }) {
  return (
    <span className={`text-xs tabular-nums ${value > max * 0.9 ? 'text-warning' : 'text-on-surface-muted'}`}>
      {value}/{max}
    </span>
  )
}

function FieldError({ id, message }: { id: string; message?: string }) {
  if (!message) return null
  return (
    <p id={id} role="alert" className="text-xs font-bold text-error">
      {message}
    </p>
  )
}

export function ProfilePage() {
  const user = useAuthStore((s) => s.user)
  const patchUser = useAuthStore((s) => s.patchUser)
  const notify = useUiStore((s) => s.notify)
  const load = useCallback(() => userApi.getProfile(), [])
  const { data: profile, error, loading, setData } = useLoad(load)
  const addressesLoad = useCallback(() => userApi.listAddresses(), [])
  const { data: addresses } = useLoad(addressesLoad)

  const [displayName, setDisplayName] = useState('')
  const [phone, setPhone] = useState('')
  const [touched, setTouched] = useState({ name: false, phone: false })
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | undefined>()
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (profile) {
      setDisplayName(profile.displayName ?? '')
      setPhone(profile.phone ?? '')
    }
  }, [profile])

  const savedName = profile?.displayName ?? ''
  const savedPhone = profile?.phone ?? ''
  const dirty = !!profile && (displayName.trim() !== savedName.trim() || phone.trim() !== savedPhone.trim())

  // Warn before the tab closes or reloads while edits are unsaved.
  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault()
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const nameError = !displayName.trim() ? 'Please enter your name.' : undefined
  const phoneError = validatePhone(phone)
  const valid = !nameError && !phoneError

  const completeness = useMemo(
    () =>
      profileCompleteness({
        displayName,
        phone,
        emailVerified: user?.emailVerified ?? false,
        addressCount: addresses ? addresses.length : null,
      }),
    [displayName, phone, user?.emailVerified, addresses],
  )

  if (loading && !profile) {
    return (
      <SectionCard title="Personal info" icon={<UserIcon className="h-5 w-5" />}>
        <CardSkeleton rows={4} />
      </SectionCard>
    )
  }
  if (error) return <ErrorAlert message={errorMessage(error)} />
  if (!profile || !user) return null

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setTouched({ name: true, phone: true })
    if (!valid) return
    setSaving(true)
    setSaveError(undefined)
    try {
      const saved = await userApi.updateProfile({ displayName: displayName.trim(), phone: phone.trim() || null })
      setData(saved)
      if (saved.displayName) patchUser({ displayName: saved.displayName })
      notify('Profile saved')
    } catch (err) {
      setSaveError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const discard = () => {
    setDisplayName(savedName)
    setPhone(savedPhone)
    setTouched({ name: false, phone: false })
    setSaveError(undefined)
  }

  const copyId = async () => {
    try {
      await navigator.clipboard.writeText(profile.userId)
      setCopied(true)
      setTimeout(() => setCopied(false), 1800)
    } catch {
      notify('Could not copy to the clipboard')
    }
  }

  const memberSince = new Date(profile.createdAt)
  const nameErrorShown = touched.name ? nameError : undefined
  const phoneErrorShown = touched.phone ? phoneError : undefined

  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
      <form onSubmit={(e) => void onSubmit(e)} noValidate className="space-y-6">
        {saveError && <ErrorAlert message={saveError} onDismiss={() => setSaveError(undefined)} />}

        <SectionCard id="identity" title="Identity" icon={<UserIcon className="h-5 w-5" />}>
          <div className="space-y-5">
            <Field label="Display name" hint="This is how your name appears on orders and across Cartwise.">
              {(id) => (
                <div className="space-y-1">
                  <div className="relative">
                    <UserIcon className="pointer-events-none absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-on-surface-muted" />
                    <input
                      id={id}
                      className={`${inputClass} pl-11 ${nameErrorShown ? '!border-error' : ''}`}
                      maxLength={DISPLAY_NAME_MAX}
                      autoComplete="name"
                      value={displayName}
                      aria-invalid={!!nameErrorShown}
                      aria-describedby={nameErrorShown ? `${id}-error` : undefined}
                      onChange={(e) => setDisplayName(e.target.value)}
                      onBlur={() => setTouched((t) => ({ ...t, name: true }))}
                    />
                  </div>
                  <div className="flex items-start justify-between gap-3">
                    <FieldError id={`${id}-error`} message={nameErrorShown} />
                    <span className="ml-auto">
                      <Counter value={displayName.length} max={DISPLAY_NAME_MAX} />
                    </span>
                  </div>
                </div>
              )}
            </Field>

            <Field label="Email address" hint="Your sign-in email can't be changed here.">
              {(id) => (
                <div className="relative">
                  <MailIcon className="pointer-events-none absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-on-surface-muted" />
                  <input id={id} className={`${inputClass} pl-11 pr-28`} value={profile.email} readOnly disabled />
                  <span className="absolute right-3 top-1/2 flex -translate-y-1/2 items-center gap-1 text-xs font-bold text-on-surface-muted">
                    <LockIcon className="h-3.5 w-3.5" /> Locked
                  </span>
                </div>
              )}
            </Field>
          </div>
        </SectionCard>

        <SectionCard id="contact" title="Contact" icon={<PhoneIcon className="h-5 w-5" />}>
          <Field label="Phone number" hint="Optional. Used only so couriers can reach you about a delivery.">
            {(id) => (
              <div className="space-y-1">
                <div className="relative">
                  <PhoneIcon className="pointer-events-none absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-on-surface-muted" />
                  <input
                    id={id}
                    type="tel"
                    inputMode="tel"
                    className={`${inputClass} pl-11 ${phoneErrorShown ? '!border-error' : ''}`}
                    maxLength={PHONE_MAX}
                    autoComplete="tel"
                    placeholder="+1 555 123 4567"
                    value={phone}
                    aria-invalid={!!phoneErrorShown}
                    aria-describedby={phoneErrorShown ? `${id}-error` : undefined}
                    onChange={(e) => setPhone(e.target.value)}
                    onBlur={() => setTouched((t) => ({ ...t, phone: true }))}
                  />
                </div>
                <FieldError id={`${id}-error`} message={phoneErrorShown} />
              </div>
            )}
          </Field>
        </SectionCard>

        <div className="sticky bottom-4 z-10 flex flex-wrap items-center gap-3 rounded-full border-2 border-ink bg-surface px-4 py-3 shadow-card">
          <p className="mr-auto flex items-center gap-2 text-sm font-bold text-on-surface" aria-live="polite">
            <span
              className={`h-2.5 w-2.5 rounded-full transition-colors duration-700 ${dirty ? 'bg-vivid-amber' : 'bg-success'}`}
              aria-hidden="true"
            />
            {dirty ? 'You have unsaved changes' : 'All changes saved'}
          </p>
          <button type="button" onClick={discard} disabled={!dirty || saving} className={secondaryButtonClass}>
            Discard
          </button>
          <button type="submit" disabled={!dirty || saving} className={primaryButtonClass}>
            {saving ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </form>

      <aside aria-label="Profile preview" className="space-y-6 xl:sticky xl:top-28 xl:self-start">
        <SectionCard id="preview" title="Preview">
          <div className="flex flex-col items-center gap-3 text-center">
            <Avatar name={displayName} email={user.email} size="xl" className="transition-all duration-1000" />
            <div className="min-w-0">
              <p className="font-display break-words text-xl text-on-surface">
                {displayNameOf({ displayName, email: user.email })}
              </p>
              <p className="break-all text-sm text-on-surface-muted">{profile.email}</p>
              {phone.trim() && <p className="text-sm text-on-surface-muted">{phone.trim()}</p>}
            </div>
          </div>
          <div className="mt-5 flex items-center gap-4 border-t-2 border-outline/20 pt-4">
            <CompletenessRing percent={completeness.percent} className="h-20 w-20" />
            <div className="text-sm">
              <p className="font-bold text-on-surface">Profile strength</p>
              <p className="text-on-surface-muted">
                {completeness.percent === 100 ? 'Complete' : `${completeness.completed} of ${completeness.total} steps`}
              </p>
              {completeness.percent < 100 && (
                <Link to="/account" className="font-bold text-primary hover:underline">
                  See what&apos;s left
                </Link>
              )}
            </div>
          </div>
        </SectionCard>

        <SectionCard id="details" title="Account details">
          <dl className="space-y-4 text-sm">
            <div className="flex items-center gap-3">
              <CalendarIcon className="h-5 w-5 shrink-0 text-on-surface-muted" />
              <div>
                <dt className="text-xs text-on-surface-muted">Member since</dt>
                <dd className="font-bold text-on-surface">
                  {Number.isNaN(memberSince.getTime()) ? '—' : memberSince.toLocaleDateString(undefined, { dateStyle: 'long' })}
                </dd>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <CheckBadgeIcon className="h-5 w-5 shrink-0 text-on-surface-muted" />
              <div>
                <dt className="text-xs text-on-surface-muted">Email</dt>
                <dd className={`font-bold ${user.emailVerified ? 'text-success' : 'text-warning'}`}>
                  {user.emailVerified ? 'Verified' : 'Not verified'}
                </dd>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <LockIcon className="h-5 w-5 shrink-0 text-on-surface-muted" />
              <div className="min-w-0 flex-1">
                <dt className="text-xs text-on-surface-muted">Account ID</dt>
                <dd className="flex items-center gap-2">
                  <code className="truncate font-mono text-xs text-on-surface">{profile.userId}</code>
                  <button
                    type="button"
                    onClick={() => void copyId()}
                    className="press shrink-0 rounded-full border-2 border-ink bg-surface p-1.5 text-on-surface hover:bg-surface-variant"
                    aria-label={copied ? 'Account ID copied' : 'Copy account ID'}
                  >
                    {copied ? <CheckBadgeIcon className="h-3.5 w-3.5 text-success" /> : <CopyIcon className="h-3.5 w-3.5" />}
                  </button>
                </dd>
              </div>
            </div>
          </dl>
        </SectionCard>
      </aside>
    </div>
  )
}
