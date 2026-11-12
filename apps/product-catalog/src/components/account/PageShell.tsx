/**
 * Common frame for the Phase 8 account sub-pages: title, optional intro and the shared loading / error states.
 */

import { ErrorAlert } from '../ErrorAlert'
import { LoadingBlock, cardClass } from '../Form'
import { errorMessage } from '../../lib/errors'

interface Props {
  id: string
  title: string
  intro?: string
  loading?: boolean
  error?: unknown
  children: React.ReactNode
}

export function PageShell({ id, title, intro, loading, error, children }: Props) {
  return (
    <section aria-labelledby={`${id}-title`} className="space-y-4">
      <div className={`${cardClass} space-y-1`}>
        <h2 id={`${id}-title`} className="font-display text-2xl font-extrabold text-on-surface">
          {title}
        </h2>
        {intro && <p className="text-sm text-on-surface-muted">{intro}</p>}
      </div>
      {loading ? <LoadingBlock label={`Loading ${title}`} /> : error ? <ErrorAlert message={errorMessage(error)} /> : children}
    </section>
  )
}

export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}
