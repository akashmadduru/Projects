/** Customer support: ticket list, new ticket (with order picker) and ticket thread with reply, close and CSAT. */

import { useCallback, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { PageShell, fmtDate } from '../../../components/account/PageShell'
import { ErrorAlert } from '../../../components/ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass, secondaryButtonClass } from '../../../components/Form'
import { useLoad } from '../../../hooks/useLoad'
import { errorMessage } from '../../../lib/errors'
import { useT } from '../../../lib/i18n'
import { orderApi } from '../../../services/orderApi'
import { supportApi } from '../../../services/supportApi'
import type { Ticket } from '../../../types'

const CATEGORIES = ['ORDERS', 'PAYMENTS', 'RETURNS', 'ACCOUNT', 'SELLERS', 'OTHER']

const statusLabel = (s: string) => s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())

export function SupportListPage() {
  const t = useT()
  const load = useCallback(() => supportApi.tickets(), [])
  const { data, loading, error } = useLoad(load)
  return (
    <PageShell id="support" title={t('Support tickets')} intro="Questions about an order or your account." loading={loading && !data} error={error}>
      <div className="flex flex-wrap gap-2">
        <Link to="/account/support/new" className={primaryButtonClass}>
          {t('New ticket')}
        </Link>
        <Link to="/help" className={secondaryButtonClass}>
          {t('Help centre')}
        </Link>
      </div>
      {data && data.length === 0 ? (
        <p className="text-on-surface-muted">No tickets yet.</p>
      ) : (
        <ul className="space-y-3">
          {data?.map((tk) => (
            <li key={tk.id}>
              <Link to={`/account/support/${tk.id}`} className={`${cardClass} flex flex-wrap items-center justify-between gap-2 hover:bg-surface-variant`}>
                <span className="font-bold text-on-surface">{tk.subject}</span>
                <span className="text-sm text-on-surface-muted">
                  {statusLabel(tk.status)} &middot; {fmtDate(tk.updatedAt)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </PageShell>
  )
}

export function NewTicketPage() {
  const t = useT()
  const navigate = useNavigate()
  const loadOrders = useCallback(() => orderApi.list(0, 20), [])
  const { data: orders } = useLoad(loadOrders)
  const [category, setCategory] = useState('ORDERS')
  const [orderId, setOrderId] = useState('')
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      const ticket = await supportApi.createTicket({
        category,
        subject: subject.trim(),
        body: body.trim(),
        ...(orderId ? { orderId } : {}),
      })
      navigate(`/account/support/${ticket.id}`, { replace: true })
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <PageShell id="new-ticket" title={t('New ticket')}>
      <form onSubmit={(e) => void submit(e)} className={`${cardClass} space-y-4`}>
        {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
        <Field label="Topic">
          {(id) => (
            <select id={id} className={inputClass} value={category} onChange={(e) => setCategory(e.target.value)}>
              {CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {statusLabel(c)}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Related order (optional)">
          {(id) => (
            <select id={id} className={inputClass} value={orderId} onChange={(e) => setOrderId(e.target.value)}>
              <option value="">None</option>
              {orders?.content.map((o) => (
                <option key={o.id} value={o.id}>
                  #{o.id.slice(0, 8)} - {fmtDate(o.placedAt)} - {statusLabel(o.status)}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label={t('Subject')}>
          {(id) => <input id={id} className={inputClass} required maxLength={200} value={subject} onChange={(e) => setSubject(e.target.value)} />}
        </Field>
        <Field label={t('Message')}>
          {(id) => (
            <textarea id={id} className={inputClass} required rows={6} maxLength={5000} value={body} onChange={(e) => setBody(e.target.value)} />
          )}
        </Field>
        <button type="submit" className={primaryButtonClass} disabled={busy || !subject.trim() || !body.trim()}>
          {busy ? 'Sending...' : t('Send')}
        </button>
      </form>
    </PageShell>
  )
}

function Csat({ ticket, onDone }: { ticket: Ticket; onDone: () => void }) {
  const [busy, setBusy] = useState(false)
  if (ticket.csat) return <p className="text-sm text-on-surface-muted">You rated this support experience {ticket.csat}/5. Thank you.</p>
  return (
    <fieldset className="space-y-2" disabled={busy}>
      <legend className="text-sm font-semibold text-on-surface">How was your support experience?</legend>
      <div className="flex gap-2">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            className={secondaryButtonClass}
            aria-label={`${n} out of 5`}
            onClick={() => {
              setBusy(true)
              supportApi.csat(ticket.id, n).then(onDone).catch(() => setBusy(false))
            }}
          >
            {n}
          </button>
        ))}
      </div>
    </fieldset>
  )
}

export function TicketDetailPage() {
  const { id = '' } = useParams()
  const load = useCallback(() => supportApi.ticket(id), [id])
  const { data, loading, error, reload } = useLoad(load)
  const [body, setBody] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | undefined>()

  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true)
    setActionError(undefined)
    try {
      await fn()
      setBody('')
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const closed = data?.status === 'CLOSED'
  const done = data?.status === 'RESOLVED' || closed

  return (
    <PageShell id="ticket" title={data?.subject ?? 'Ticket'} intro={data ? `${statusLabel(data.status)} - ${statusLabel(data.category)}` : undefined} loading={loading && !data} error={error}>
      {data && (
        <>
          {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}
          {data.orderId && (
            <p className="text-sm">
              Order: <Link className="font-semibold text-primary hover:underline" to={`/orders/${data.orderId}`}>#{data.orderId.slice(0, 8)}</Link>
            </p>
          )}
          <ol className="space-y-3" aria-label="Conversation">
            {data.messages?.map((m) => (
              <li
                key={m.id}
                className={`${cardClass} ${m.authorType === 'CUSTOMER' ? 'ml-6 bg-primary-container/40' : 'mr-6'}`}
              >
                <p className="text-xs font-bold uppercase tracking-wider text-on-surface-muted">
                  {m.authorType === 'CUSTOMER' ? 'You' : m.authorType === 'AGENT' ? 'Support' : m.authorType === 'BOT' ? 'Assistant' : 'System'} &middot; {fmtDate(m.createdAt)}
                </p>
                <p className="mt-1 whitespace-pre-wrap text-on-surface">{m.body}</p>
              </li>
            ))}
          </ol>
          {!closed && (
            <form
              className={`${cardClass} space-y-3`}
              onSubmit={(e) => {
                e.preventDefault()
                void run(() => supportApi.reply(id, body.trim()))
              }}
            >
              <Field label="Reply">
                {(fid) => <textarea id={fid} className={inputClass} rows={4} maxLength={5000} required value={body} onChange={(e) => setBody(e.target.value)} />}
              </Field>
              <div className="flex flex-wrap gap-2">
                <button type="submit" className={primaryButtonClass} disabled={busy || !body.trim()}>
                  Send reply
                </button>
                <button type="button" className={secondaryButtonClass} disabled={busy} onClick={() => void run(() => supportApi.close(id))}>
                  Close ticket
                </button>
              </div>
            </form>
          )}
          {done && <div className={cardClass}><Csat ticket={data} onDone={reload} /></div>}
        </>
      )}
    </PageShell>
  )
}
