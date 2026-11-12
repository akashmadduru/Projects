/**
 * Header account entry: "Sign in" when anonymous; an avatar menu when signed in. "Profile" opens
 * the account dashboard.
 */

import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Avatar } from './account/Avatar'
import {
  ChevronDownIcon,
  DashboardIcon,
  HeartIcon,
  LogoutIcon,
  MapPinIcon,
  PackageIcon,
  SettingsIcon,
  UserIcon,
} from './Icons'
import { displayNameOf } from '../lib/account'
import { useAuthStore } from '../stores'

const action =
  'press group relative flex items-center gap-2 rounded-full border-2 border-transparent px-3 py-1.5 text-sm font-bold text-on-brand hover:border-on-brand/40 hover:bg-on-brand/10'

const menuItem =
  'group flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left text-sm font-bold text-on-surface transition-colors duration-500 hover:bg-surface-variant focus-visible:bg-surface-variant focus-visible:outline-none'

export function AccountMenu() {
  const status = useAuthStore((s) => s.status)
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onPointer = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        const items = [...(menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])]
        if (items.length === 0) return
        e.preventDefault()
        const index = items.indexOf(document.activeElement as HTMLElement)
        const next = e.key === 'ArrowDown' ? (index + 1) % items.length : (index - 1 + items.length) % items.length
        items[next]?.focus()
      }
    }
    document.addEventListener('mousedown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  if (status === 'unknown') return null

  if (status === 'anonymous' || !user) {
    return (
      <Link to="/login" className={action}>
        <UserIcon className="h-5 w-5" />
        Sign in
      </Link>
    )
  }

  const name = displayNameOf(user)
  const close = () => setOpen(false)
  const signOut = async () => {
    close()
    await logout()
    navigate('/')
  }

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={`${action} !gap-2 !px-1.5 !py-1 sm:!pr-3`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Account menu for ${name}`}
      >
        <Avatar name={user.displayName} email={user.email} size="sm" className="!shadow-none" />
        <span className="hidden max-w-[7rem] truncate sm:inline">{name}</span>
        <ChevronDownIcon
          className={`hidden h-4 w-4 transition-transform duration-700 sm:block ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {open && (
        <div
          ref={menuRef}
          role="menu"
          aria-label="Account"
          className="animate-slide-up absolute right-0 top-full z-50 mt-3 w-72 origin-top-right overflow-hidden rounded-[1.5rem] border-2 border-ink bg-surface shadow-card-hover"
        >
          <div className="bg-brand-gradient flex items-center gap-3 border-b-2 border-ink p-4 text-on-brand">
            <Avatar name={user.displayName} email={user.email} size="md" />
            <div className="min-w-0">
              <p className="font-display truncate text-lg leading-tight">{name}</p>
              <p className="truncate text-xs text-on-brand/80">{user.email}</p>
            </div>
          </div>

          <div className="space-y-0.5 p-2">
            <Link role="menuitem" to="/account" onClick={close} className={menuItem}>
              <DashboardIcon className="h-5 w-5 text-primary" />
              <span className="flex-1">
                Profile
                <span className="block text-xs font-medium text-on-surface-muted">Your dashboard &amp; activity</span>
              </span>
            </Link>
            <Link role="menuitem" to="/account/orders" onClick={close} className={menuItem}>
              <PackageIcon className="h-5 w-5 text-vivid-sky" />
              Orders
            </Link>
            <Link role="menuitem" to="/account/addresses" onClick={close} className={menuItem}>
              <MapPinIcon className="h-5 w-5 text-vivid-amber" />
              Addresses
            </Link>
            <Link role="menuitem" to="/wishlist" onClick={close} className={menuItem}>
              <HeartIcon className="h-5 w-5 text-vivid-pink" />
              Wishlist
            </Link>
            <Link role="menuitem" to="/account/settings" onClick={close} className={menuItem}>
              <SettingsIcon className="h-5 w-5 text-on-surface-muted" />
              Settings
            </Link>
          </div>

          <div className="border-t-2 border-outline/20 p-2">
            <button type="button" role="menuitem" onClick={() => void signOut()} className={`${menuItem} text-error`}>
              <LogoutIcon className="h-5 w-5" />
              Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
