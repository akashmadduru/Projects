/**
 * Site footer with the Cartwise brand
 */

import { Link } from 'react-router-dom'
import { Starburst } from './Atomic'
import { LogoMark } from './Logo'
import { toSearchString } from '../hooks'
import { useConsent } from '../lib/consent'
import { useT } from '../lib/i18n'

const shop = [
  { label: 'All products', search: '' },
  { label: 'Top deals', search: toSearchString({ minDiscount: 20, sort: 'discount' }) },
  { label: 'Top rated', search: toSearchString({ minRating: 4, sort: 'rating' }) },
  { label: 'New arrivals', search: toSearchString({ sort: 'newest' }) },
]

export function Footer() {
  const t = useT()
  const reopenConsent = useConsent((s) => s.reopen)
  return (
    <footer className="bg-brand-gradient relative isolate mt-12 overflow-hidden border-t-2 border-ink text-on-brand">
      <div aria-hidden="true" className="retro-stripes h-2 border-b-2 border-ink" />
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
        <div className="bg-atomic-pattern absolute inset-0 !opacity-15" />
        <Starburst className="animate-twinkle absolute right-[8%] top-10 h-12 w-12 text-vivid-amber/70" />
        <Starburst sparkle className="animate-twinkle absolute bottom-8 right-[30%] h-6 w-6 text-on-brand/60" />
      </div>
      <div className="mx-auto grid max-w-[1400px] gap-8 px-4 py-10 sm:px-6 md:grid-cols-[2fr_1fr_1fr] lg:px-8">
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <LogoMark />
            <span className="font-display text-2xl [text-shadow:2px_2px_0_hsl(var(--color-ink))]">
              Cart<span className="text-accent">wise</span>
            </span>
          </div>
          <p className="max-w-sm text-sm text-on-brand/75">
            Modern living made marvelous. Honest prices, real ratings and deals worth your time.
          </p>
        </div>

        <nav aria-label="Shop">
          <h3 className="mb-3 text-sm uppercase tracking-[0.18em] text-vivid-amber">{t('Shop')}</h3>
          <ul className="space-y-2 text-sm">
            {shop.map((link) => (
              <li key={link.label}>
                <Link to={{ pathname: '/', search: link.search }} className="inline-block text-on-brand/85 transition-all duration-500 hover:translate-x-1 hover:text-accent">
                  {t(link.label)}
                </Link>
              </li>
            ))}
          </ul>
        </nav>

        <nav aria-label="Your account">
          <h3 className="mb-3 text-sm uppercase tracking-[0.18em] text-vivid-amber">{t('Your Cartwise')}</h3>
          <ul className="space-y-2 text-sm">
            <li>
              <Link to="/wishlist" className="inline-block text-on-brand/85 transition-all duration-500 hover:translate-x-1 hover:text-accent">
                {t('Wishlist')}
              </Link>
            </li>
            <li>
              <Link to="/help" className="inline-block text-on-brand/85 transition-all duration-500 hover:translate-x-1 hover:text-accent">
                {t('Help centre')}
              </Link>
            </li>
            <li>
              <Link to="/account/privacy" className="inline-block text-on-brand/85 transition-all duration-500 hover:translate-x-1 hover:text-accent">
                {t('Privacy')}
              </Link>
            </li>
            <li>
              <button type="button" onClick={reopenConsent} className="inline-block text-on-brand/85 transition-all duration-500 hover:translate-x-1 hover:text-accent">
                {t('Cookie preferences')}
              </button>
            </li>
            <li>
              <a
                href={import.meta.env.VITE_ADMIN_URL ?? 'http://localhost:5173'}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-block text-on-brand/85 transition-all duration-500 hover:translate-x-1 hover:text-accent"
              >
                {t('Sell on Cartwise')}
                <span className="sr-only"> (opens in a new tab)</span>
              </a>
            </li>
          </ul>
        </nav>
      </div>
      <div className="border-t border-on-brand/10">
        <p className="mx-auto max-w-[1400px] px-4 py-4 text-xs text-on-brand/60 sm:px-6 lg:px-8">
          &copy; {new Date().getFullYear()} Cartwise. All rights reserved.
        </p>
      </div>
    </footer>
  )
}
