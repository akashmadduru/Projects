/**
 * Site header: animated gradient brand bar with logo, search and account actions
 */

import { Link } from 'react-router-dom'
import { CartIcon, HeartIcon } from './Icons'
import { Logo } from './Logo'
import { SearchBar } from './SearchBar'
import { AccountMenu } from './AccountMenu'
import { NotificationBell } from './NotificationBell'
import { ThemeToggle } from './ThemeToggle'
import { useScrolled } from '../hooks/useScrolled'
import { useCartStore, useUiStore, useWishlistStore } from '../stores'

function CountBadge({ count }: { count: number }) {
  if (count === 0) return null
  return (
    // Re-keying on the count restarts the bump animation every time it changes.
    <span
      key={count}
      className="animate-bump absolute -right-2 -top-2 flex h-5 min-w-5 items-center justify-center rounded-full border-2 border-ink bg-vivid-amber px-1 text-[11px] font-bold text-ink"
    >
      {count > 99 ? '99+' : count}
    </span>
  )
}

const action =
  'press group relative flex items-center gap-2 rounded-full border-2 border-transparent px-3 py-1.5 text-sm font-bold text-on-brand hover:border-on-brand/40 hover:bg-on-brand/10'

export function Header() {
  const cartCount = useCartStore((s) => s.items.reduce((sum, item) => sum + item.quantity, 0))
  const wishlistCount = useWishlistStore((s) => s.items.length)
  const openCart = useUiStore((s) => s.openCart)
  const scrolled = useScrolled()

  return (
    <header
      className={`bg-brand-gradient sticky top-0 z-50 border-b-2 border-ink transition-shadow duration-700 ${
        scrolled ? 'shadow-[0_4px_0_hsl(var(--color-ink)/0.25)]' : ''
      }`}
    >
      <div
        className={`mx-auto flex max-w-[1400px] flex-wrap items-center gap-x-4 gap-y-3 px-4 transition-[padding] duration-700 sm:px-6 lg:flex-nowrap lg:px-8 ${
          scrolled ? 'py-2' : 'py-3'
        }`}
      >
        <Logo />

        <div className="order-last w-full lg:order-none lg:mx-4 lg:max-w-2xl lg:flex-1">
          <SearchBar />
        </div>

        <div className="ml-auto flex items-center gap-1">
          <Link to="/wishlist" className={action} aria-label={`Wishlist, ${wishlistCount} items`}>
            <span className="relative">
              <HeartIcon className="w-6 h-6 transition-transform duration-700 group-hover:scale-110 group-hover:text-vivid-pink" />
              <CountBadge count={wishlistCount} />
            </span>
            <span className="hidden sm:inline">Wishlist</span>
          </Link>
          <button type="button" onClick={openCart} className={action} aria-label={`Cart, ${cartCount} items`}>
            <span className="relative">
              <CartIcon className="w-6 h-6 transition-transform duration-700 group-hover:-rotate-6 group-hover:scale-110" />
              <CountBadge count={cartCount} />
            </span>
            <span className="hidden sm:inline">Cart</span>
          </button>
          <NotificationBell />
          <AccountMenu />
          <ThemeToggle />
        </div>
      </div>
      {/* Diner stripe - mustard, tangerine, cherry. */}
      <div aria-hidden="true" className="retro-stripes h-1.5 border-t-2 border-ink" />
      {/* Marquee light string hanging under the stripe; decorative, see .bulb-string. */}
      <div aria-hidden="true" className="bulb-string absolute inset-x-0 -bottom-3 h-3" />
    </header>
  )
}
