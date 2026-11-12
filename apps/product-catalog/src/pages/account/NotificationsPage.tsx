/**
 * Account: full notification list with unread filter, paging and mark-as-read.
 */

import { useCallback, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { cardClass, secondaryButtonClass } from '../../components/Form'
import { fmtRelative } from '../../components/notificationTime'
import { PageShell } from '../../components/account/PageShell'
import { useLoad } from '../../hooks/useLoad'
import { engageApi } from '../../services/engageApi'
import type { AppNotification } from '../../types'

export function NotificationsPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const load = useCallback(() => engageApi.notifications(page, unreadOnly), [page, unreadOnly])
  const { data, loading, error, reload } = useLoad(load)

  const open = async (n: AppNotification) => {
    if (!n.readAt) await engageApi.markRead(n.id).catch(() => undefined)
    if (n.link) navigate(n.link)
    else reload()
  }

  return (
    <PageShell id="notifications" title="Notifications" loading={loading && !data} error={error}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <label className="flex items-center gap-2 text-sm text-on-surface">
          <input type="checkbox" checked={unreadOnly} onChange={(e) => { setUnreadOnly(e.target.checked); setPage(0) }} />
          Unread only
        </label>
        <div className="flex gap-2">
          <button type="button" className={secondaryButtonClass} onClick={() => void engageApi.markAllRead().then(reload)}>
            Mark all read
          </button>
          <Link to="/account/notification-settings" className={secondaryButtonClass}>
            Preferences
          </Link>
        </div>
      </div>
      {data && (
        <div className={`${cardClass} !p-0`}>
          {data.content.length === 0 ? (
            <p className="p-5 text-sm text-on-surface-muted">Nothing here.</p>
          ) : (
            <ul className="divide-y divide-outline">
              {data.content.map((n) => (
                <li key={n.id}>
                  <button type="button" onClick={() => void open(n)} className="flex w-full items-start gap-3 px-5 py-3 text-left hover:bg-surface-variant">
                    {!n.readAt && <span aria-label="Unread" className="mt-2 h-2 w-2 shrink-0 rounded-full bg-vivid-pink" />}
                    <span className="min-w-0 flex-1">
                      <span className="block font-semibold text-on-surface">{n.title}</span>
                      <span className="block text-sm text-on-surface-muted">{n.body}</span>
                      <span className="block text-xs text-on-surface-muted">{fmtRelative(n.createdAt)}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      <div className="flex justify-between">
        <button type="button" className={secondaryButtonClass} disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Newer
        </button>
        <button type="button" className={secondaryButtonClass} disabled={!data || data.content.length < 20} onClick={() => setPage((p) => p + 1)}>
          Older
        </button>
      </div>
    </PageShell>
  )
}
