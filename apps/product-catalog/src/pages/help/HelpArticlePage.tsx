/** One FAQ article with a helpful yes/no vote. */

import { useCallback, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ErrorAlert } from '../../components/ErrorAlert'
import { LoadingBlock, cardClass, secondaryButtonClass } from '../../components/Form'
import { useHead } from '../../hooks/useHead'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { useT } from '../../lib/i18n'
import { MarkdownLite } from '../../lib/markdownLite'
import { supportApi } from '../../services/supportApi'

export function HelpArticlePage() {
  const t = useT()
  const { slug = '' } = useParams()
  const load = useCallback(() => supportApi.article(slug), [slug])
  const { data, loading, error } = useLoad(load)
  const [voted, setVoted] = useState(false)
  useHead({ title: data ? `${data.title} | Cartwise` : `${t('Help centre')} | Cartwise` })

  const vote = (helpful: boolean) => {
    setVoted(true)
    supportApi.articleFeedback(slug, helpful).catch(() => undefined)
  }

  if (loading && !data) return <LoadingBlock label="Loading article" />
  if (error || !data) return <ErrorAlert message={errorMessage(error)} />

  return (
    <article className="mx-auto max-w-3xl space-y-5">
      <Link to="/help" className="text-sm font-semibold text-primary hover:underline">
        &larr; {t('Help centre')}
      </Link>
      <h1 className="font-display text-3xl font-bold text-on-surface">{data.title}</h1>
      <div className={cardClass}>
        <MarkdownLite source={data.body ?? ''} />
      </div>
      <div className={`${cardClass} flex flex-wrap items-center gap-3`} aria-live="polite">
        {voted ? (
          <p className="font-semibold text-on-surface">Thanks for the feedback.</p>
        ) : (
          <>
            <p className="font-semibold text-on-surface">{t('Was this helpful?')}</p>
            <button type="button" className={secondaryButtonClass} onClick={() => vote(true)}>
              {t('Yes')}
            </button>
            <button type="button" className={secondaryButtonClass} onClick={() => vote(false)}>
              {t('No')}
            </button>
          </>
        )}
      </div>
    </article>
  )
}

export default HelpArticlePage
