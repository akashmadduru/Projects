/**
 * Create / edit a review: star rating, optional title and text
 */

import { useState } from 'react'
import { ErrorAlert } from './ErrorAlert'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from './Form'
import { StarPicker } from './StarPicker'
import { errorMessage } from '../lib/errors'
import { ratingApi } from '../services/ratingApi'
import { useUiStore } from '../stores'
import type { Review, ReviewInput, ReviewMedia } from '../types'

const TITLE_MAX = 200
const TEXT_MAX = 5000
const MEDIA_MAX = 4
const MEDIA_BYTES = 5 * 1024 * 1024
const MEDIA_TYPES = ['image/jpeg', 'image/png', 'image/webp']

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
  const notify = useUiStore((s) => s.notify)
  const [files, setFiles] = useState<File[]>([])
  const [media, setMedia] = useState<ReviewMedia[]>(existing?.media ?? [])
  const [fileError, setFileError] = useState<string | undefined>()

  const addFiles = (list: FileList | null) => {
    if (!list) return
    const next = [...files]
    setFileError(undefined)
    for (const file of Array.from(list)) {
      if (!MEDIA_TYPES.includes(file.type)) setFileError('Only JPEG, PNG or WebP photos are allowed.')
      else if (file.size > MEDIA_BYTES) setFileError('Each photo must be 5 MB or smaller.')
      else if (media.length + next.length >= MEDIA_MAX) setFileError(`You can attach up to ${MEDIA_MAX} photos.`)
      else next.push(file)
    }
    setFiles(next)
  }

  const removeExisting = async (m: ReviewMedia) => {
    if (!existing) return
    try {
      await ratingApi.removeMedia(existing.id, m.id)
      setMedia((list) => list.filter((x) => x.id !== m.id))
    } catch (err) {
      setError(errorMessage(err))
    }
  }

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
      let failed = 0
      for (const file of files) {
        try {
          await ratingApi.uploadMedia(saved.id, file)
        } catch {
          failed++
        }
      }
      if (failed > 0) notify(`Review saved, but ${failed} photo${failed === 1 ? '' : 's'} could not be uploaded.`)
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
      <div className="space-y-2">
        <p className="text-sm font-semibold text-on-surface">Add photos (optional)</p>
        <div className="flex flex-wrap gap-2">
          {media.map((m) => (
            <div key={m.id} className="relative h-16 w-16 overflow-hidden rounded-lg border-2 border-outline">
              <img src={m.url} alt="Attached to your review" className="h-full w-full object-cover" />
              <button
                type="button"
                onClick={() => void removeExisting(m)}
                aria-label="Remove photo"
                className="absolute right-0 top-0 rounded-bl bg-ink/80 px-1 text-xs text-white"
              >
                ×
              </button>
            </div>
          ))}
          {files.map((f, i) => (
            <div key={`${f.name}-${i}`} className="relative h-16 w-16 overflow-hidden rounded-lg border-2 border-outline">
              <img src={URL.createObjectURL(f)} alt={`Selected file ${f.name}`} className="h-full w-full object-cover" onLoad={(e) => URL.revokeObjectURL(e.currentTarget.src)} />
              <button
                type="button"
                onClick={() => setFiles((list) => list.filter((_, j) => j !== i))}
                aria-label={`Remove ${f.name}`}
                className="absolute right-0 top-0 rounded-bl bg-ink/80 px-1 text-xs text-white"
              >
                ×
              </button>
            </div>
          ))}
        </div>
        {media.length + files.length < MEDIA_MAX && (
          <>
            <label htmlFor="review-photos" className={`${secondaryButtonClass} cursor-pointer`}>
              Choose photos
            </label>
            <input
              id="review-photos"
              type="file"
              accept={MEDIA_TYPES.join(',')}
              multiple
              disabled={submitting}
              onChange={(e) => {
                addFiles(e.target.files)
                e.target.value = ''
              }}
              className="sr-only"
            />
          </>
        )}
        <p className="text-xs text-on-surface-muted">Up to {MEDIA_MAX} photos, JPEG / PNG / WebP, 5 MB each.</p>
        {fileError && (
          <p role="alert" className="text-sm font-semibold text-error">
            {fileError}
          </p>
        )}
      </div>
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
