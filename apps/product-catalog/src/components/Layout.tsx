/**
 * App shell: atomic backdrop, header, animated route outlet, footer, and layout-level overlays
 */

import { useEffect } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { CartDrawer } from './CartDrawer'
import { CompareTray } from './CompareTray'
import { ChatWidget } from './chat/ChatWidget'
import { CookieBanner } from './CookieBanner'
import { Footer } from './Footer'
import { Header } from './Header'
import { Atom, Boomerang, Starburst } from './Atomic'
import { ArrowUpIcon } from './Icons'
import { Toaster } from './Toaster'
import { VerifyEmailBanner } from './VerifyEmailBanner'
import { useReferral } from '../hooks/useReferral'
import { useScrolled } from '../hooks/useScrolled'
import { useT } from '../lib/i18n'

/** Starburst laminate wallpaper with a few slow-moving space-age motifs. Purely decorative. */
function AtomicBackdrop() {
  return (
    <div aria-hidden="true" className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      <div className="bg-atomic-pattern absolute inset-0" />
      <div className="absolute -right-24 -top-24 text-primary opacity-[0.1]">
        <Atom className="h-[28rem] w-[28rem] animate-spin-slower" spinning={false} />
      </div>
      <Starburst className="animate-twinkle absolute left-[6%] top-[22%] h-10 w-10 text-vivid-amber/60" />
      <Starburst
        sparkle
        className="animate-twinkle absolute right-[10%] top-[58%] h-8 w-8 text-accent-2/50"
      />
      <Starburst className="animate-twinkle absolute bottom-[12%] left-[38%] h-6 w-6 text-primary/40" />
      <div className="animate-drift absolute bottom-[18%] left-[4%] text-vivid-pink/20">
        <Boomerang className="h-16 w-32" />
      </div>
    </div>
  )
}

function BackToTop() {
  const visible = useScrolled(700)
  return (
    <button
      type="button"
      onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
      aria-label="Back to top"
      tabIndex={visible ? 0 : -1}
      aria-hidden={!visible}
      className={`press bg-cta-gradient fixed bottom-6 right-6 z-40 rounded-full border-2 border-ink p-3 text-on-accent shadow-cta transition-all duration-700 ease-[var(--ease-spring)] hover:-translate-y-1 ${
        visible ? 'translate-y-0 scale-100 opacity-100' : 'pointer-events-none translate-y-6 scale-75 opacity-0'
      }`}
    >
      <ArrowUpIcon className="w-5 h-5" />
    </button>
  )
}

export function Layout() {
  const { pathname } = useLocation()
  const t = useT()
  useReferral()

  // A new page starts at the top; filter/sort changes on the same page keep the scroll position.
  useEffect(() => {
    window.scrollTo({ top: 0 })
  }, [pathname])

  return (
    <div className="relative isolate flex min-h-screen flex-col">
      <AtomicBackdrop />
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[80] focus:rounded-lg focus:bg-surface focus:px-4 focus:py-2"
      >
        {t('Skip to content')}
      </a>
      <Header />
      <VerifyEmailBanner />
      {/* Keyed on the path so each page navigation replays the entrance transition. */}
      <main key={pathname} id="main" tabIndex={-1} className="animate-page-in mx-auto w-full max-w-[1400px] flex-1 px-4 py-6 sm:px-6 lg:px-8">
        <Outlet />
      </main>
      <Footer />
      <BackToTop />
      <CartDrawer />
      <CompareTray />
      <Toaster />
      <ChatWidget />
      <CookieBanner />
    </div>
  )
}
