/**
 * Account: referral code, share link and stats.
 */

import { useCallback } from 'react'
import { cardClass, secondaryButtonClass } from '../../components/Form'
import { CopyIcon } from '../../components/Icons'
import { PageShell } from '../../components/account/PageShell'
import { useLoad } from '../../hooks/useLoad'
import { engageApi } from '../../services/engageApi'
import { useUiStore } from '../../stores'

export function ReferralsPage() {
  const notify = useUiStore((s) => s.notify)
  const load = useCallback(() => engageApi.referrals(), [])
  const { data, loading, error } = useLoad(load)

  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      notify('Copied')
    } catch {
      notify('Could not copy. Select and copy it manually.')
    }
  }
  const share = async (link: string) => {
    if (navigator.share) {
      try {
        await navigator.share({ title: 'Join me on Cartwise', url: link })
        return
      } catch {
        // Dismissed share sheet: fall through to copy.
      }
    }
    await copy(link)
  }

  return (
    <PageShell
      id="referrals"
      title="Refer a friend"
      intro="You and your friend each get 100 coins after their first delivered order."
      loading={loading && !data}
      error={error}
    >
      {data && (
        <>
          <div className={`${cardClass} space-y-3`}>
            <p className="text-sm font-semibold text-on-surface-muted">Your code</p>
            <p className="font-display text-3xl tracking-widest text-on-surface">{data.code}</p>
            <p className="break-all rounded-lg border-2 border-outline bg-surface-variant px-3 py-2 text-sm text-on-surface">{data.link}</p>
            <div className="flex flex-wrap gap-2">
              <button type="button" className={secondaryButtonClass} onClick={() => void copy(data.link)}>
                <CopyIcon className="h-4 w-4" /> Copy link
              </button>
              <button type="button" className={secondaryButtonClass} onClick={() => void share(data.link)}>
                Share
              </button>
            </div>
          </div>
          <dl className={`${cardClass} grid grid-cols-2 gap-4 text-center`}>
            <div>
              <dt className="text-sm text-on-surface-muted">Invited</dt>
              <dd className="font-display text-3xl text-on-surface">{data.invited}</dd>
            </div>
            <div>
              <dt className="text-sm text-on-surface-muted">Rewarded</dt>
              <dd className="font-display text-3xl text-on-surface">{data.rewarded}</dd>
            </div>
          </dl>
        </>
      )}
    </PageShell>
  )
}
