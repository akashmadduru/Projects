/**
 * Share the product: native share sheet when available, otherwise copy the link and toast.
 */

import { CopyIcon } from './Icons'
import { secondaryButtonClass } from './Form'
import { useUiStore } from '../stores'

export function ShareButton({ title, url }: { title: string; url: string }) {
  const notify = useUiStore((s) => s.notify)

  const share = async () => {
    try {
      if (typeof navigator.share === 'function') {
        await navigator.share({ title, url })
        return
      }
      await navigator.clipboard.writeText(url)
      notify('Link copied to clipboard')
    } catch (e) {
      // The user dismissing the native sheet is not an error.
      if ((e as { name?: string }).name !== 'AbortError') notify('Could not share. Copy the address bar link instead.')
    }
  }

  return (
    <button type="button" onClick={() => void share()} className={secondaryButtonClass}>
      <CopyIcon className="h-4 w-4" />
      Share
    </button>
  )
}
