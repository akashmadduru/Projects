/**
 * Latest notifications inside the header bell dropdown (lazy chunk).
 */

import { useCallback } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ErrorAlert } from './ErrorAlert'
import { useLoad } from '../hooks/useLoad'
import { errorMessage } from '../lib/errors'
import { engageApi } from '../services/engageApi'
import { fmtRelative } from './notificationTime'
import type { AppNotification } from '../types'

interface Props {
  onUnread: (n: number) => void
  onClose: () => void
}

export default function NotificationDropdown({ onUnread, onClose }: Props) {
  const navigate = useNavigate()
  const load = useCallback(() => engageApi.notifications(0, false), [])
  const { data, loading, error, reload } = useLoad(load)

  const open = async (n: AppNotification) => {
    if (!n.readAt) {
      await engageApi.markRead(n.id).catch(() => undefined)
      onUnread(Math.max((data?.unreadCount ?? 1) - 1, 0))
    }
    onClose()
    if (n.link) navigate(n.link)
    else reload()
  }

  const readAll = async () => {
    await engageApi.markAllRead().catch(() => undefined)
    onUnread(0)
    reload()
  }

  if (loading && !data) return <p className="p-3 text-sm text-on-surface-muted">Loading...</p>
  if (error) return <ErrorAlert message={errorMessage(error)} />
  const items = (data?.content ?? []).slice(0, 6)

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between px-1">
        <h2 className="font-bold text-on-surface">Notifications</h2>
        <button type="button" onClick={() => void readAll()} className="text-xs font-semibold text-primary hover:underline">
          Mark all read
        </button>
      </div>
      {items.length === 0 ? (
        <p className="px-1 py-4 text-sm text-on-surface-muted">You are all caught up.</p>
      ) : (
        <ul className="max-h-80 divide-y divide-outline overflow-y-auto">
          {items.map((n) => (
            <li key={n.id}>
              <button type="button" onClick={() => void open(n)} className="block w-full px-1 py-2 text-left hover:bg-surface-variant">
                <span className="flex items-start gap-2">
                  {!n.readAt && <span aria-label="Unread" className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-vivid-pink" />}
                  <span className="min-w-0">
                    <span className="block text-sm font-semibold text-on-surface">{n.title}</span>
                    <span className="line-clamp-2 block text-xs text-on-surface-muted">{n.body}</span>
                    <span className="block text-[11px] text-on-surface-muted">{fmtRelative(n.createdAt)}</span>
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex justify-between border-t-2 border-outline px-1 pt-2 text-sm font-semibold">
        <Link to="/account/notifications" onClick={onClose} className="text-primary hover:underline">
          See all
        </Link>
        <Link to="/account/notification-settings" onClick={onClose} className="text-on-surface-muted hover:text-primary">
          Settings
        </Link>
      </div>
    </div>
  )
}
