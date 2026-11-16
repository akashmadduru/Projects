/**
 * Shown while this tab renders an unpublished theme from a `#cwpreview=` link (opened from the
 * admin console's Theme & branding editor). Nothing here is saved; "Exit preview" returns to the
 * published theme.
 */

import { useT } from '../lib/i18n'
import { useBrandStore } from '../stores/brandStore'

export function ThemePreviewBanner() {
  const t = useT()
  const preview = useBrandStore((s) => s.preview)
  const exitPreview = useBrandStore((s) => s.exitPreview)
  if (!preview) return null
  return (
    <div
      role="status"
      className="sticky top-0 z-[60] flex flex-wrap items-center justify-center gap-x-3 gap-y-1 border-b-2 border-ink bg-warning px-4 py-1.5 text-center text-xs font-semibold text-on-accent"
    >
      <span>{t('Theme preview — this look is not published and only you can see it.')}</span>
      <button
        type="button"
        onClick={exitPreview}
        className="rounded-full border-2 border-ink bg-surface px-3 py-0.5 text-on-surface hover:bg-surface-variant"
      >
        {t('Exit preview')}
      </button>
    </div>
  )
}
