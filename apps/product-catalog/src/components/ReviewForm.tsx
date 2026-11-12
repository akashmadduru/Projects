/**
 * Create / edit a review: star rating, optional title and text
 */

import { useState } from 'react'
import { ErrorAlert } from './ErrorAlert'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from './Form'
import { StarPicker } from './StarPicker'
import { errorMessage } from '../lib/errors'
import { ratingApi } from '../services/ratingApi'
import type { Review, ReviewInput } from '../types'

const TITLE_MAX = 200
const TEXT_MAX = 5000

interface Props {
  productId: string
  existing?: Review
  onSaved: (review: Review) => void
  onCancel?: () => void
}

export function ReviewForm({ productId, existing, onSaved, onCancel }: Props) {
  const [rating, setRating] = useState(existing?.ratingValue ?? 0)
  const [title, setTitle] = useState(existing?.reviewTitle ?? '')
  const [text, setText] = useState(existing?.reviewText ?? '')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const [ratingMissing, setRatingMissing] = useState(false)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (rating < 1) {
      setRatingMissing(true)
      return
    }
    setSubmitting(true)
    setError(undefined)
    const input: ReviewInput = {
      ratingValue: rating,
      ...(title.trim() && { reviewTitle: title.trim() }),
      ...(text.trim() && { reviewText: text.trim() }),
    }
    try {
      const saved = existing
        ? await ratingApi.update(existing.id, input, existing.version)
        : await ratingApi.create(productId, input)
      onSaved(saved)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <StarPicker
        name={`rating-${existing?.id ?? 'new'}`}
        value={rating}
        onChange={(n) => {
          setRating(n)
          setRatingMissing(false)
        }}
        disabled={submitting}
      />
      {ratingMissing && (
        <p role="alert" className="text-sm font-semibold text-error">
          Please choose a star rating.
        </p>
      )}
      <Field label="Review title (optional)" hint={`${title.length}/${TITLE_MAX}`}>
        {(id) => (
          <input
            id={id}
            type="text"
            value={title}
            maxLength={TITLE_MAX}
            onChange={(e) => setTitle(e.target.value)}
            disabled={submitting}
            className={inputClass}
            placeholder="Sum it up in a few words"
          />
        )}
      </Field>
      <Field label="Your review (optional)" hint={`${text.length}/${TEXT_MAX}`}>
        {(id) => (
          <textarea
            id={id}
            value={text}
            maxLength={TEXT_MAX}
            rows={5}
            onChange={(e) => setText(e.target.value)}
            disabled={submitting}
            className={inputClass}
            placeholder="What did you like or dislike?"
          />
        )}
      </Field>
      <div className="flex flex-wrap gap-3">
        <button type="submit" disabled={submitting} className={primaryButtonClass}>
          {submitting ? 'Saving...' : existing ? 'Save changes' : 'Submit review'}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} disabled={submitting} className={secondaryButtonClass}>
            Cancel
          </button>
        )}
      </div>
    </form>
  )
}
