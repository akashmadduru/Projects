/**
 * Settings: appearance, account security status and session
 */

import { useNavigate } from 'react-router-dom'
import { PhoneSection } from '../../components/account/PhoneSection'
import { SectionCard } from '../../components/account/SectionCard'
import { secondaryButtonClass } from '../../components/Form'
import { CheckBadgeIcon, CheckIcon, LogoutIcon, MoonIcon, SettingsIcon, SunIcon } from '../../components/Icons'
import { useAuthStore, useThemeStore } from '../../stores'

type Mode = 'light' | 'dark'

/** A tiny stylised page so the choice is visual, not just a label. */
function ThemeSwatch({ mode }: { mode: Mode }) {
  const dark = mode === 'dark'
  return (
    <span
      aria-hidden="true"
      className="block h-24 overflow-hidden rounded-2xl border-2 border-ink"
      style={{ background: dark ? '#0b1822' : '#f6efd9' }}
    >
      <span className="block h-4 border-b-2" style={{ background: dark ? '#15323a' : '#1b7469', borderColor: '#1d2b3a' }} />
      <span className="mt-3 flex gap-2 px-3">
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            className="block h-12 flex-1 rounded-lg border-2"
            style={{ background: dark ? '#13222d' : '#fff8e6', borderColor: dark ? '#5a7a87' : '#1d2b3a' }}
          />
        ))}
      </span>
    </span>
  )
}

function ThemeOption({
  mode,
  label,
  icon,
  selected,
  onSelect,
}: {
  mode: Mode
  label: string
  icon: React.ReactNode
  selected: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      className={`press group space-y-3 rounded-[1.5rem] border-2 p-3 text-left transition-all duration-700 ${
        selected
          ? 'border-ink bg-primary-container shadow-[4px_4px_0_hsl(var(--color-ink))]'
          : 'border-outline bg-surface hover:-translate-y-0.5 hover:shadow-card'
      }`}
    >
      <ThemeSwatch mode={mode} />
      <span className="flex items-center gap-2 px-1 font-bold text-on-surface">
        {icon}
        {label}
        {selected && (
          <span className="ml-auto flex h-6 w-6 animate-pop items-center justify-center rounded-full border-2 border-ink bg-success text-white dark:text-ink">
            <CheckIcon className="h-3.5 w-3.5" />
          </span>
        )}
      </span>
    </button>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 py-3">
      <dt className="text-sm text-on-surface-muted">{label}</dt>
      <dd className="text-sm font-bold text-on-surface">{children}</dd>
    </div>
  )
}

export function SettingsPage() {
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const mode = useThemeStore((s) => s.mode)
  const setMode = useThemeStore((s) => s.setMode)
  const navigate = useNavigate()

  if (!user) return null

  const signOut = async () => {
    await logout()
    navigate('/')
  }

  return (
    <div className="space-y-6">
      <SectionCard id="appearance" title="Appearance" icon={<SettingsIcon className="h-5 w-5" />}>
        <div role="radiogroup" aria-label="Theme" className="grid gap-4 sm:grid-cols-2">
          <ThemeOption
            mode="light"
            label="Daylight"
            icon={<SunIcon className="h-5 w-5" />}
            selected={mode === 'light'}
            onSelect={() => setMode('light')}
          />
          <ThemeOption
            mode="dark"
            label="Night sky"
            icon={<MoonIcon className="h-5 w-5" />}
            selected={mode === 'dark'}
            onSelect={() => setMode('dark')}
          />
        </div>
        <p className="mt-3 text-xs text-on-surface-muted">Saved on this device.</p>
      </SectionCard>

      <SectionCard id="security" title="Account & security" icon={<CheckBadgeIcon className="h-5 w-5" />}>
        <dl className="divide-y-2 divide-outline/20">
          <Row label="Email">{user.email}</Row>
          <Row label="Email verification">
            <span
              className={`inline-flex items-center gap-1.5 rounded-full border-2 border-ink px-2.5 py-0.5 text-xs ${
                user.emailVerified ? 'bg-success/20 text-success' : 'bg-vivid-amber/30 text-on-surface'
              }`}
            >
              <CheckBadgeIcon className="h-3.5 w-3.5" />
              {user.emailVerified ? 'Verified' : 'Not verified'}
            </span>
          </Row>
          <Row label="Account status">
            <span className="capitalize">{user.status.toLowerCase()}</span>
          </Row>
          <Row label="Roles">
            <span className="flex flex-wrap justify-end gap-1.5">
              {user.roles.length === 0 ? '—' : null}
              {user.roles.map((role) => (
                <span key={role} className="rounded-full border-2 border-ink bg-surface-variant px-2.5 py-0.5 text-xs">
                  {role.replace(/^ROLE_/, '').replace(/_/g, ' ').toLowerCase()}
                </span>
              ))}
            </span>
          </Row>
        </dl>
      </SectionCard>

      <SectionCard id="phone" title="Mobile number" icon={<CheckBadgeIcon className="h-5 w-5" />}>
        <PhoneSection />
      </SectionCard>

      <SectionCard id="session"title="Session" icon={<LogoutIcon className="h-5 w-5" />}>
        <div className="flex flex-wrap items-center justify-between gap-4">
          <p className="max-w-md text-sm text-on-surface-muted">
            You&apos;re signed in on this device. Signing out also clears your cart and wishlist from this browser — they stay
            safe in your account.
          </p>
          <button type="button" onClick={() => void signOut()} className={`${secondaryButtonClass} !text-error`}>
            <LogoutIcon className="h-4 w-4" /> Sign out
          </button>
        </div>
      </SectionCard>
    </div>
  )
}
