/** Floating chat launcher. The panel (and its API client usage) is code-split and fetched on first open. */

import { lazy, Suspense, useState } from 'react'
import { useT } from '../../lib/i18n'

const ChatPanel = lazy(() => import('./ChatPanel'))

export function ChatWidget() {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [mounted, setMounted] = useState(false)

  return (
    <>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => {
          setMounted(true)
          setOpen((o) => !o)
        }}
        className="press fixed bottom-6 left-4 z-[64] rounded-full border-2 border-ink bg-primary-container px-4 py-2.5 text-sm font-bold text-on-primary-container shadow-cta"
      >
        {t('Chat with us')}
      </button>
      {mounted && open && (
        <Suspense fallback={null}>
          <ChatPanel onClose={() => setOpen(false)} />
        </Suspense>
      )}
    </>
  )
}
