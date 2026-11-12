/**
 * Customer questions and answers for a product. Loaded lazily; reading is public, asking / answering /
 * upvoting needs a signed-in user.
 */

import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ErrorAlert } from './ErrorAlert'
import { inputClass, primaryButtonClass, secondaryButtonClass } from './Form'
import { CheckBadgeIcon } from './Icons'
import { ThemedSelect } from './ThemedSelect'
import { errorMessage } from '../lib/errors'
import { ratingApi } from '../services/ratingApi'
import { useAuthStore, useUiStore } from '../stores'
import type { Answer, Question, QuestionSort } from '../types'

const SORTS: { value: QuestionSort; label: string }[] = [
  { value: 'top', label: 'Top voted' },
  { value: 'newest', label: 'Newest' },
]
const QUESTION_MAX = 1000
const ANSWER_MAX = 2000

function Composer({
  label,
  max,
  submitLabel,
  onSubmit,
  onCancel,
}: {
  label: string
  max: number
  submitLabel: string
  onSubmit: (text: string) => Promise<void>
  onCancel?: () => void
}) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()
  return (
    <form
      className="space-y-2"
      onSubmit={(e) => {
        e.preventDefault()
        if (!text.trim()) return
        setBusy(true)
        setError(undefined)
        onSubmit(text.trim())
          .then(() => setText(''))
          .catch((err: unknown) => setError(errorMessage(err)))
          .finally(() => setBusy(false))
      }}
    >
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <label className="sr-only" htmlFor={`composer-${label}`}>
        {label}
      </label>
      <textarea
        id={`composer-${label}`}
        value={text}
        maxLength={max}
        rows={3}
        onChange={(e) => setText(e.target.value)}
        placeholder={label}
        disabled={busy}
        className={inputClass}
      />
      <div className="flex flex-wrap items-center gap-2">
        <button type="submit" disabled={busy || !text.trim()} className={primaryButtonClass}>
          {busy ? 'Posting...' : submitLabel}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} className={secondaryButtonClass}>
            Cancel
          </button>
        )}
        <span className="text-xs text-on-surface-muted">
          {text.length}/{max}
        </span>
      </div>
    </form>
  )
}

function QuestionItem({
  question,
  authenticated,
  requireAuth,
}: {
  question: Question
  authenticated: boolean
  requireAuth: () => boolean
}) {
  const notify = useUiStore((s) => s.notify)
  const [answers, setAnswers] = useState<Answer[]>(question.answers)
  const [answerCount, setAnswerCount] = useState(question.answerCount)
  const [upvotes, setUpvotes] = useState(question.upvotes)
  const [answering, setAnswering] = useState(false)
  const [loadingAll, setLoadingAll] = useState(false)

  const upvoteQuestion = () => {
    if (!requireAuth()) return
    ratingApi
      .upvoteQuestion(question.id)
      .then(() => setUpvotes((n) => n + 1))
      .catch((e: unknown) => notify(errorMessage(e)))
  }
  const upvoteAnswer = (id: string) => {
    if (!requireAuth()) return
    ratingApi
      .upvoteAnswer(id)
      .then(() => setAnswers((list) => list.map((a) => (a.id === id ? { ...a, upvotes: a.upvotes + 1 } : a))))
      .catch((e: unknown) => notify(errorMessage(e)))
  }
  const showAll = () => {
    setLoadingAll(true)
    ratingApi
      .answers(question.id, 0)
      .then(setAnswers)
      .catch((e: unknown) => notify(errorMessage(e)))
      .finally(() => setLoadingAll(false))
  }

  return (
    <li className="space-y-3 py-4 first:pt-0">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-semibold text-on-surface">Q: {question.text}</p>
          <p className="text-xs text-on-surface-muted">
            {question.authorName} · <time dateTime={question.createdAt}>{new Date(question.createdAt).toLocaleDateString()}</time>
          </p>
        </div>
        <button
          type="button"
          onClick={upvoteQuestion}
          aria-label={`Upvote this question (${upvotes} upvotes)`}
          className={`${secondaryButtonClass} !px-3 !py-1 !text-xs`}
        >
          ▲ {upvotes}
        </button>
      </div>

      {answers.length > 0 && (
        <ul className="space-y-2 border-l-2 border-outline pl-4">
          {answers.map((a) => (
            <li key={a.id} className="space-y-1">
              <p className="whitespace-pre-line break-words text-on-surface">A: {a.text}</p>
              <p className="flex flex-wrap items-center gap-2 text-xs text-on-surface-muted">
                <span className="font-semibold text-on-surface">{a.authorName}</span>
                {a.verifiedBuyer && (
                  <span className="inline-flex items-center gap-0.5 rounded bg-primary-container px-1.5 py-0.5 font-semibold text-on-primary-container">
                    <CheckBadgeIcon className="h-3.5 w-3.5" /> Verified buyer
                  </span>
                )}
                <button
                  type="button"
                  onClick={() => upvoteAnswer(a.id)}
                  aria-label={`Upvote this answer (${a.upvotes} upvotes)`}
                  className="font-semibold hover:text-primary"
                >
                  ▲ {a.upvotes}
                </button>
              </p>
            </li>
          ))}
        </ul>
      )}

      <div className="flex flex-wrap gap-3">
        {answerCount > answers.length && (
          <button type="button" onClick={showAll} disabled={loadingAll} className="text-sm font-semibold text-primary hover:underline">
            {loadingAll ? 'Loading...' : `See all ${answerCount} answers`}
          </button>
        )}
        {!answering && (
          <button
            type="button"
            onClick={() => requireAuth() && setAnswering(true)}
            className="text-sm font-semibold text-primary hover:underline"
          >
            Answer
          </button>
        )}
      </div>
      {answering && authenticated && (
        <Composer
          label={`Your answer to: ${question.text.slice(0, 40)}`}
          max={ANSWER_MAX}
          submitLabel="Post answer"
          onCancel={() => setAnswering(false)}
          onSubmit={async (text) => {
            const created = await ratingApi.answer(question.id, text)
            setAnswers((list) => [...list, created])
            setAnswerCount((n) => n + 1)
            setAnswering(false)
            notify('Answer posted')
          }}
        />
      )}
    </li>
  )
}

export default function QASection({ productId }: { productId: string }) {
  const location = useLocation()
  const authenticated = useAuthStore((s) => s.status === 'authenticated')
  const notify = useUiStore((s) => s.notify)
  const [sort, setSort] = useState<QuestionSort>('top')
  const [questions, setQuestions] = useState<Question[]>([])
  const [pages, setPages] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | undefined>()
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let live = true
    setLoading(true)
    setError(undefined)
    ratingApi
      .questions(productId, 0, sort)
      .then((res) => {
        if (!live) return
        setQuestions(res.questions)
        setPages(1)
        setTotalPages(res.totalPages)
      })
      .catch((e: unknown) => {
        if (live) setError(errorMessage(e))
      })
      .finally(() => {
        if (live) setLoading(false)
      })
    return () => {
      live = false
    }
  }, [productId, sort, reloadKey])

  const loadMore = () => {
    ratingApi
      .questions(productId, pages, sort)
      .then((res) => {
        setQuestions((prev) => [...prev, ...res.questions.filter((q) => !prev.some((p) => p.id === q.id))])
        setPages((n) => n + 1)
        setTotalPages(res.totalPages)
      })
      .catch((e: unknown) => setError(errorMessage(e)))
  }

  const requireAuth = useCallback(() => {
    if (authenticated) return true
    notify('Sign in to join the conversation')
    return false
  }, [authenticated, notify])

  return (
    <div className="space-y-5">
      <div className="space-y-2">
        <h3 className="font-bold text-on-surface">Ask a question</h3>
        {authenticated ? (
          <Composer
            label="Ask about this product"
            max={QUESTION_MAX}
            submitLabel="Post question"
            onSubmit={async (text) => {
              await ratingApi.ask(productId, text)
              notify('Question posted')
              setReloadKey((n) => n + 1)
            }}
          />
        ) : (
          <Link to={`/login?next=${encodeURIComponent(location.pathname)}`} className={primaryButtonClass}>
            Sign in to ask
          </Link>
        )}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-outline pt-4">
        <h3 className="font-bold text-on-surface">Customer questions</h3>
        <ThemedSelect id="qa-sort" label="Sort by" value={sort} options={SORTS} onChange={setSort} />
      </div>

      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      {loading ? (
        <div role="status" aria-label="Loading questions" className="h-24 w-full rounded-2xl skeleton-loading" />
      ) : questions.length === 0 ? (
        !error && <p className="text-sm text-on-surface-muted">No questions yet. Be the first to ask.</p>
      ) : (
        <ul className="divide-y divide-outline">
          {questions.map((q) => (
            <QuestionItem key={q.id} question={q} authenticated={authenticated} requireAuth={requireAuth} />
          ))}
        </ul>
      )}
      {!loading && pages < totalPages && (
        <button type="button" onClick={loadMore} className={secondaryButtonClass}>
          Load more questions
        </button>
      )}
    </div>
  )
}
