/**
 * Rate the seller of a delivered shipment. Loaded lazily from the order page.
 */

import { useState } from 'react'
import { ErrorAlert } from './ErrorAlert'
import { Field, inputClass, primaryButtonClass } from './Form'
import { StarPicker } from './StarPicker'
import { errorMessage } from '../lib/errors'
import { sellerApi } from '../services/sellerApi'
import { useUiStore } from '../stores'

interface Props {
  sellerId: string
  sellerName: string
  shipmentId: string
  onDone: () => void
}

const COMMENT_MAX = 500

export default function SellerFeedbackForm({ sellerId, sellerName, shipmentId, onDone }: Props) {
  const notify = useUiStore((s) => s.notify)
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (rating < 1) return
    setBusy(true)
    setError(undefined)
    try {
      await sellerApi.submitFeedback(sellerId, { shipmentId, rating, ...(comment.trim() ? { comment: comment.trim() } : {}) })
      notify('Thanks for rating the seller')
      onDone()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} className="space-y-3 rounded-xl border-2 border-outline p-3" aria-label={`Rate ${sellerName}`}>
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <StarPicker name={`seller-rating-${shipmentId}`} value={rating} onChange={setRating} disabled={busy} />
      <Field label="Comment (optional)">
        {(id) => (
          <textarea id={id} rows={2} maxLength={COMMENT_MAX} value={comment} onChange={(e) => setComment(e.target.value)} className={inputClass} />
        )}
      </Field>
      <button type="submit" disabled={busy || rating < 1} className={primaryButtonClass}>
        {busy ? 'Submitting...' : 'Submit seller rating'}
      </button>
    </form>
  )
}
