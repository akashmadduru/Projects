/**
 * Header bell with an unread badge. The dropdown itself is lazy-loaded on first open so it never
 * weighs on the main bundle. The count refreshes every minute and on navigation.
 */

import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { BellIcon } from './Icons'
import { engageApi } from '../services/engageApi'
import { useAuthStore } from '../stores'

const NotificationDropdown = lazy(() => import('./NotificationDropdown'))

const POLL_MS = 60_000

export function NotificationBell() {
  const authenticated = useAuthStore((s) => s.status === 'authenticated')
  const { pathname } = useLocation()
  const [unread, setUnread] = useState(0)
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!authenticated) {
      setUnread(0)
      return
    }
    let live = true
    const poll = () => {
      engageApi
        .notifications(0, true)
        .then((p) => live && setUnread(p.unreadCount ?? 0))
        .catch(() => undefined)
    }
    poll()
    const timer = setInterval(poll, POLL_MS)
    return () => {
      live = false
      clearInterval(timer)
    }
  }, [authenticated, pathname])

  useEffect(() => setOpen(false), [pathname])

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  if (!authenticated) return null

  return (
    <div ref={root} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={`Notifications, ${unread} unread`}
        className="press group relative flex items-center gap-2 rounded-full border-2 border-transparent px-3 py-1.5 text-sm font-bold text-on-brand hover:border-on-brand/40 hover:bg-on-brand/10"
      >
        <span className="relative">
          <BellIcon className="h-6 w-6 transition-transform duration-700 group-hover:rotate-12" />
          {unread > 0 && (
            <span className="absolute -right-2 -top-2 flex h-5 min-w-5 items-center justify-center rounded-full border-2 border-ink bg-vivid-pink px-1 text-[11px] font-bold text-white dark:text-ink">
              {unread > 99 ? '99+' : unread}
            </span>
          )}
        </span>
      </button>
      {open && (
        <div className="absolute right-0 top-full z-50 mt-2 w-[min(22rem,calc(100vw-2rem))] rounded-[1.5rem] border-2 border-ink bg-surface p-3 shadow-card-hover">
          <Suspense fallback={<p className="p-3 text-sm text-on-surface-muted">Loading...</p>}>
            <NotificationDropdown onUnread={setUnread} onClose={() => setOpen(false)} />
          </Suspense>
        </div>
      )}
    </div>
  )
}
