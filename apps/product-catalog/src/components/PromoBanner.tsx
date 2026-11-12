/**
 * Space-age hero on the unfiltered first page - styled like a 1958 magazine advertisement
 */

import { Link } from 'react-router-dom'
import { Atom, Boomerang, Seal, Starburst } from './Atomic'
import { toSearchString } from '../hooks'

export function PromoBanner() {
  return (
    <section
      aria-labelledby="promo-title"
      className="bg-brand-gradient relative isolate overflow-hidden rounded-[2.5rem] border-2 border-ink px-6 py-10 text-on-brand shadow-card-hover sm:px-12 sm:py-14"
    >
      {/* Decorative motifs. */}
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
        <div className="bg-atomic-pattern absolute inset-0 !opacity-20" />
        <div className="absolute -right-10 top-1/2 hidden -translate-y-1/2 text-on-brand/90 md:block">
          <Atom className="h-80 w-80 drop-shadow-[4px_4px_0_hsl(var(--color-ink))]" />
        </div>
        <div className="animate-drift absolute right-[30%] top-6 text-vivid-amber/70">
          <Boomerang className="h-10 w-20" />
        </div>
        <div className="animate-drift absolute bottom-6 right-[18%] text-vivid-pink/60" style={{ animationDelay: '-4s' }}>
          <Boomerang className="h-8 w-16" />
        </div>
        <Starburst className="animate-twinkle absolute left-[46%] top-[18%] h-8 w-8 text-vivid-amber" />
        <Starburst sparkle className="animate-twinkle absolute bottom-[22%] left-[58%] h-6 w-6 text-on-brand" />
      </div>

      {/* Starburst "sticker", like a price flash on a vintage ad. */}
      <Link
        to={{ pathname: '/', search: toSearchString({ minDiscount: 20, sort: 'discount' }) }}
        className="group absolute right-[21rem] top-10 z-10 hidden h-32 w-32 items-center justify-center xl:flex"
        aria-label="Deals of the day: 20% off or more"
      >
        <Seal className="absolute inset-0 h-full w-full text-deal drop-shadow-[3px_3px_0_hsl(var(--color-ink))] transition-transform duration-1000 group-hover:rotate-45" />
        <span className="font-display relative rotate-[-12deg] text-center text-sm leading-tight text-white">
          DEALS
          <br />
          <span className="text-2xl">20%</span>
          <br />
          OFF+
        </span>
      </Link>

      <div className="relative max-w-xl space-y-5 animate-slide-up">
        <span className="inline-flex items-center gap-2 rounded-full border-2 border-ink bg-vivid-amber px-4 py-1 text-xs font-bold uppercase tracking-[0.2em] text-ink shadow-cta">
          <Starburst sparkle className="h-3.5 w-3.5" /> The Space Age is here
        </span>
        <h1
          id="promo-title"
          className="font-display text-4xl leading-[1.05] [text-shadow:3px_3px_0_hsl(var(--color-ink))] sm:text-6xl"
        >
          Shop the World
          <br />
          of <span className="text-vivid-amber">Tomorrow</span>, Today!
        </h1>
        <p className="max-w-lg text-lg text-on-brand/90">
          Modern living made marvelous. Honest prices, real ratings, and 20% or more off across
          electronics, home, fashion and more.
        </p>
        <div className="flex flex-wrap gap-4 pt-2">
          <Link
            to={{ pathname: '/', search: toSearchString({ minDiscount: 20, sort: 'discount' }) }}
            className="press shine bg-cta-gradient inline-flex items-center gap-2 rounded-full border-2 border-ink px-7 py-3 font-bold text-on-accent shadow-cta hover:-translate-y-0.5 hover:shadow-[5px_5px_0_hsl(var(--color-ink))]"
          >
            Shop top deals
          </Link>
          <Link
            to={{ pathname: '/', search: toSearchString({ minRating: 4, sort: 'rating' }) }}
            className="press inline-flex items-center gap-2 rounded-full border-2 border-ink bg-surface px-7 py-3 font-bold text-on-surface shadow-cta hover:-translate-y-0.5 hover:shadow-[5px_5px_0_hsl(var(--color-ink))]"
          >
            <Starburst className="h-4 w-4 text-accent-2" /> Top rated
          </Link>
        </div>
      </div>
    </section>
  )
}
