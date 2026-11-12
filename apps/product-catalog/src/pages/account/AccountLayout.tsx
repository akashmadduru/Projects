/**
 * Account area shell: identity hero, section navigation and the active section
 */

import { NavLink, Outlet } from 'react-router-dom'
import { Atom, Starburst } from '../../components/Atomic'
import { Avatar } from '../../components/account/Avatar'
import {
  CalendarIcon,
  CheckBadgeIcon,
  DashboardIcon,
  HeartIcon,
  MapPinIcon,
  PackageIcon,
  SettingsIcon,
  UserIcon,
} from '../../components/Icons'
import { displayNameOf } from '../../lib/account'
import { useAuthStore, useWishlistStore } from '../../stores'

const navLink = ({ isActive }: { isActive: boolean }) =>
  `press group flex shrink-0 items-center gap-2.5 rounded-full border-2 px-4 py-2 text-sm font-bold transition-all duration-500 lg:w-full ${
    isActive
      ? 'border-ink bg-vivid-amber text-ink shadow-[3px_3px_0_hsl(var(--color-ink))] lg:translate-x-1'
      : 'border-transparent text-on-surface hover:bg-surface-variant lg:hover:translate-x-1'
  }`

const chip =
  'inline-flex items-center gap-1.5 rounded-full border border-on-brand/30 bg-on-brand/10 px-3 py-1 text-xs font-bold text-on-brand'

function prettyRole(role: string): string {
  return role
    .replace(/^ROLE_/, '')
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

function formatMonthYear(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
}

export function AccountLayout() {
  const user = useAuthStore((s) => s.user)
  const wishlistCount = useWishlistStore((s) => s.items.length)
  if (!user) return null

  const name = displayNameOf(user)
  const memberSince = formatMonthYear(user.createdAt)

  return (
    <div className="space-y-6">
      <section
        aria-labelledby="account-title"
        className="bg-brand-gradient relative isolate overflow-hidden rounded-[2rem] border-2 border-ink p-6 text-on-brand shadow-card-hover sm:p-8"
      >
        <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
          <div className="bg-atomic-pattern absolute inset-0 !opacity-20" />
          <div className="absolute -right-12 -top-12 text-on-brand/25">
            <Atom className="h-56 w-56" />
          </div>
          <Starburst className="animate-twinkle absolute bottom-6 right-[28%] h-8 w-8 text-vivid-amber/80" />
        </div>

        <div className="flex flex-wrap items-center gap-5 sm:gap-6">
          <Avatar name={user.displayName} email={user.email} size="xl" />
          <div className="min-w-0 space-y-2">
            <p className="text-xs font-bold uppercase tracking-[0.2em] text-vivid-amber">Your Cartwise account</p>
            <h1
              id="account-title"
              className="font-display break-words text-3xl leading-tight [text-shadow:3px_3px_0_hsl(var(--color-ink))] sm:text-4xl"
            >
              {name}
            </h1>
            <p className="break-all text-sm text-on-brand/85">{user.email}</p>
            <ul className="flex flex-wrap gap-2 pt-1">
              <li className={chip}>
                <CheckBadgeIcon className="h-3.5 w-3.5" />
                {user.emailVerified ? 'Email verified' : 'Email not verified'}
              </li>
              {memberSince && (
                <li className={chip}>
                  <CalendarIcon className="h-3.5 w-3.5" />
                  Member since {memberSince}
                </li>
              )}
              {user.roles.map((role) => (
                <li key={role} className={chip}>
                  {prettyRole(role)}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </section>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[250px_minmax(0,1fr)]">
        <nav
          aria-label="Account sections"
          className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-2 [scrollbar-width:none] sm:-mx-6 sm:px-6 lg:sticky lg:top-28 lg:mx-0 lg:flex-col lg:self-start lg:overflow-visible lg:rounded-[1.75rem] lg:border-2 lg:border-outline lg:bg-surface lg:p-3 lg:shadow-card"
        >
          <NavLink to="/account" end className={navLink}>
            <DashboardIcon className="h-5 w-5" />
            Dashboard
          </NavLink>
          <NavLink to="/account/profile" className={navLink}>
            <UserIcon className="h-5 w-5" />
            Personal info
          </NavLink>
          <NavLink to="/account/orders" className={navLink}>
            <PackageIcon className="h-5 w-5" />
            Orders
          </NavLink>
          <NavLink to="/account/addresses" className={navLink}>
            <MapPinIcon className="h-5 w-5" />
            Addresses
          </NavLink>
          <NavLink to="/wishlist" className={navLink}>
            <HeartIcon className="h-5 w-5" />
            Wishlist
            {wishlistCount > 0 && (
              <span className="ml-auto rounded-full border-2 border-ink bg-vivid-pink px-2 text-xs font-bold text-white dark:text-ink">
                {wishlistCount}
              </span>
            )}
          </NavLink>
          <NavLink to="/account/settings" className={navLink}>
            <SettingsIcon className="h-5 w-5" />
            Settings
          </NavLink>
        </nav>

        <div className="min-w-0">
          <Outlet />
        </div>
      </div>
    </div>
  )
}
