/**
 * Heart toggle for the wishlist: pops and bursts when saved. Never navigates when nested in a card.
 */

import { useState } from 'react'
import { Starburst } from './Atomic'
import { HeartIcon } from './Icons'
import { useUiStore, useWishlistStore } from '../stores'

interface Props {
  productId: string
  productName: string
  variant?: 'overlay' | 'outline'
}

export function WishlistButton({ productId, productName, variant = 'overlay' }: Props) {
  const saved = useWishlistStore((s) => s.items.some((item) => item.productId === productId))
  const toggle = useWishlistStore((s) => s.toggleItem)
  const notify = useUiStore((s) => s.notify)
  // Incremented on each save so the pop/burst animation replays.
  const [saves, setSaves] = useState(0)

  const onClick = (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    toggle(productId)
    if (!saved) setSaves((n) => n + 1)
    notify(saved ? `Removed ${productName} from your wishlist` : `Saved ${productName} to your wishlist`)
  }

  const look =
    variant === 'overlay'
      ? 'p-2 rounded-full border-2 border-ink bg-surface shadow-[2px_2px_0_hsl(var(--color-ink))] hover:-translate-y-0.5 hover:shadow-[3px_3px_0_hsl(var(--color-ink))]'
      : 'p-3 rounded-full border-2 border-ink bg-surface shadow-cta hover:-translate-y-0.5 hover:bg-vivid-pink/10'

  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={saved}
      aria-label={saved ? `Remove ${productName} from wishlist` : `Save ${productName} to wishlist`}
      className={`press relative ${look} ${saved ? 'text-vivid-pink' : 'text-on-surface-muted hover:text-vivid-pink'}`}
    >
      {saves > 0 && saved && (
        <span
          key={`burst-${saves}`}
          aria-hidden="true"
          className="animate-burst pointer-events-none absolute -inset-2 text-vivid-pink/70"
        >
          <Starburst className="h-full w-full" />
        </span>
      )}
      <span key={saved ? `on-${saves}` : 'off'} className={`block ${saved && saves > 0 ? 'animate-pop' : ''}`}>
        <HeartIcon filled={saved} className="w-5 h-5" />
      </span>
    </button>
  )
}
