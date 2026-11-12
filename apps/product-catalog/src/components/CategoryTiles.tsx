/**
 * Colorful "Shop by category" tiles for the catalog home - one vivid hue per top-level category
 */

import { Link } from 'react-router-dom'
import { Starburst } from './Atomic'
import { BookIcon, DumbbellIcon, HomeIcon, LaptopIcon, ShirtIcon, TagIcon, WatchIcon } from './Icons'
import { toSearchString, type CategoryNode } from '../hooks'

const HUES = ['vivid-sky', 'vivid-violet', 'vivid-pink', 'vivid-coral', 'vivid-amber', 'vivid-lime', 'vivid-teal'] as const

const ICONS: [RegExp, typeof TagIcon][] = [
  [/electr|comput|tech|audio/i, LaptopIcon],
  [/book|read/i, BookIcon],
  [/fashion|cloth|apparel|wear/i, ShirtIcon],
  [/home|garden|kitchen|furni/i, HomeIcon],
  [/sport|fitness|outdoor/i, DumbbellIcon],
  [/access|watch|jewel/i, WatchIcon],
]

function iconFor(name: string) {
  return ICONS.find(([pattern]) => pattern.test(name))?.[1] ?? TagIcon
}

export function CategoryTiles({ categories }: { categories: CategoryNode[] }) {
  if (categories.length === 0) return null

  return (
    <section aria-labelledby="categories-heading" className="space-y-3">
      <h2 id="categories-heading" className="flex items-center gap-2 font-display text-2xl text-on-surface">
        <Starburst className="h-6 w-6 animate-twinkle text-accent-2" />
        Shop by category
      </h2>
      <ul className="grid grid-cols-3 gap-3 sm:grid-cols-4 lg:grid-cols-6">
        {categories.map((category, i) => {
          const hue = HUES[i % HUES.length]
          const Icon = iconFor(category.name)
          return (
            <li key={category.id} style={{ animation: `slideUpStagger 0.75s cubic-bezier(0.22,1,0.36,1) ${i * 0.08}s both` }}>
              <Link
                to={{ pathname: '/', search: toSearchString({ categoryId: category.id }) }}
                className="press group flex h-full flex-col items-center gap-2 rounded-[1.75rem] border-2 border-outline bg-surface p-4 text-center shadow-card transition-all duration-450 ease-[cubic-bezier(0.34,1.56,0.64,1)] hover:-translate-x-1 hover:-translate-y-1 hover:shadow-card-hover"
                style={{ ['--tile' as string]: `var(--color-${hue})` }}
              >
                <span className="relative flex h-16 w-16 items-center justify-center">
                  {/* Orbit ring that whirls around the badge on hover. */}
                  <svg
                    viewBox="0 0 64 64"
                    aria-hidden="true"
                    className="absolute -inset-2 h-20 w-20 text-ink transition-transform duration-1000 ease-out group-hover:rotate-[180deg]"
                  >
                    <ellipse cx="32" cy="32" rx="30" ry="11" fill="none" stroke="currentColor" strokeWidth="2" transform="rotate(-30 32 32)" />
                    <circle cx="58" cy="17" r="3.5" fill="hsl(var(--tile))" stroke="currentColor" strokeWidth="1.5" />
                  </svg>
                  <span
                    className="relative flex h-14 w-14 items-center justify-center rounded-full border-2 border-ink text-white shadow-[3px_3px_0_hsl(var(--color-ink))] transition-transform duration-700 ease-[cubic-bezier(0.34,1.56,0.64,1)] group-hover:scale-110"
                    style={{ background: 'hsl(var(--tile))' }}
                  >
                    <Icon className="w-7 h-7 drop-shadow-[1px_1px_0_hsl(var(--color-ink))]" />
                  </span>
                </span>
                <span className="font-display text-base text-on-surface">{category.name}</span>
                {category.children.length > 0 && (
                  <span className="text-xs text-on-surface-muted">
                    {category.children.length} {category.children.length === 1 ? 'collection' : 'collections'}
                  </span>
                )}
              </Link>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
