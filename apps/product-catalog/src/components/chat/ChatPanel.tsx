/** Chat panel (lazy-loaded): rule-based support assistant with suggestions, article links and actions. */

import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { primaryButtonClass, secondaryButtonClass } from '../Form'
import { errorMessage } from '../../lib/errors'
import { useT } from '../../lib/i18n'
import { supportApi } from '../../services/supportApi'
import type { ChatAction, ChatReply } from '../../types'

interface Entry {
  from: 'me' | 'bot'
  text: string
  reply?: ChatReply
}

function sessionId(): string {
  try {
    let id = sessionStorage.getItem('chat-session')
    if (!id) {
      id = crypto.randomUUID()
      sessionStorage.setItem('chat-session', id)
    }
    return id
  } catch {
    return 'anonymous-session'
  }
}

export default function ChatPanel({ onClose }: { onClose: () => void }) {
  const t = useT()
  const navigate = useNavigate()
  const [entries, setEntries] = useState<Entry[]>([
    { from: 'bot', text: 'Hi! I can help with orders, returns, payments and more.', reply: { reply: '', suggestions: ['Where is my order?', 'Return or refund', 'Talk to a human'] } },
  ])
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const endRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: 'end' })
  }, [entries])

  const send = async (message: string) => {
    const trimmed = message.trim()
    if (!trimmed || busy) return
    setEntries((e) => [...e, { from: 'me', text: trimmed }])
    setText('')
    setBusy(true)
    try {
      const reply = await supportApi.chat(sessionId(), trimmed)
      setEntries((e) => [...e, { from: 'bot', text: reply.reply, reply }])
    } catch (err) {
      setEntries((e) => [...e, { from: 'bot', text: errorMessage(err) }])
    } finally {
      setBusy(false)
    }
  }

  const act = (action: ChatAction) => {
    onClose()
    if (action.type === 'CREATE_TICKET') navigate('/account/support/new')
    else if (action.orderId) navigate(`/orders/${action.orderId}`)
    else navigate('/account/orders')
  }

  const actionLabel = (a: ChatAction) =>
    a.type === 'CREATE_TICKET' ? 'Create a support ticket' : a.type === 'TRACK_ORDER' ? 'Track this order' : 'Start a return'

  return (
    <div
      role="dialog"
      aria-label={t('Chat with us')}
      className="fixed bottom-20 left-3 z-[65] flex h-[28rem] max-h-[70vh] w-[22rem] max-w-[calc(100vw-1.5rem)] flex-col overflow-hidden rounded-[1.5rem] border-2 border-ink bg-surface shadow-card"
    >
      <div className="flex items-center justify-between border-b-2 border-ink bg-primary-container px-4 py-2">
        <p className="font-bold text-on-primary-container">{t('Chat with us')}</p>
        <button type="button" onClick={onClose} className="rounded-lg px-2 text-xl font-bold text-on-primary-container" aria-label={t('Close')}>
          &times;
        </button>
      </div>
      <div className="flex-1 space-y-3 overflow-y-auto p-3 text-sm" aria-live="polite">
        {entries.map((e, i) => (
          <div key={i} className={e.from === 'me' ? 'text-right' : ''}>
            <p
              className={`inline-block max-w-[85%] whitespace-pre-wrap rounded-2xl px-3 py-2 ${
                e.from === 'me' ? 'bg-primary-container text-on-primary-container' : 'bg-surface-variant text-on-surface'
              }`}
            >
              {e.text}
            </p>
            {e.reply?.articles && e.reply.articles.length > 0 && (
              <ul className="mt-1 space-y-1">
                {e.reply.articles.map((a) => (
                  <li key={a.slug}>
                    <Link to={`/help/${a.slug}`} onClick={onClose} className="font-semibold text-primary underline">
                      {a.title}
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            {e.reply?.action && (
              <button type="button" className={`${primaryButtonClass} mt-2 !px-3 !py-1.5 !text-xs`} onClick={() => act(e.reply!.action!)}>
                {actionLabel(e.reply.action)}
              </button>
            )}
            {e.reply?.suggestions && i === entries.length - 1 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {e.reply.suggestions.map((s) => (
                  <button key={s} type="button" className={`${secondaryButtonClass} !px-3 !py-1 !text-xs`} onClick={() => void send(s)}>
                    {s}
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
        {busy && <p className="text-on-surface-muted">Typing...</p>}
        <div ref={endRef} />
      </div>
      <form
        className="flex gap-2 border-t-2 border-ink p-2"
        onSubmit={(e) => {
          e.preventDefault()
          void send(text)
        }}
      >
        <label htmlFor="chat-input" className="sr-only">
          {t('Message')}
        </label>
        <input
          id="chat-input"
          className="min-w-0 flex-1 rounded-full border-2 border-ink bg-surface px-3 py-1.5 text-sm text-on-surface"
          value={text}
          maxLength={500}
          onChange={(e) => setText(e.target.value)}
        />
        <button type="submit" className={`${primaryButtonClass} !px-4 !py-1.5 !text-sm`} disabled={busy || !text.trim()}>
          {t('Send')}
        </button>
      </form>
    </div>
  )
}
