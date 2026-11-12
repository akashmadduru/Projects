/**
 * Sticky bar at the bottom of the page listing the products selected for comparison.
 */

import { Link, useLocation } from 'react-router-dom'
import { CloseIcon } from './Icons'
import { useCompareStore } from '../stores/compareStore'

export function CompareTray() {
  const items = useCompareStore((s) => s.items)
  const remove = useCompareStore((s) => s.remove)
  const clear = useCompareStore((s) => s.clear)
  const { pathname } = useLocation()
  if (items.length === 0 || pathname === '/compare') return null

  return (
    <aside
      aria-label="Compare tray"
      className="fixed inset-x-0 bottom-0 z-40 border-t-2 border-ink bg-surface px-4 py-3 shadow-card-hover"
    >
      <div className="mx-auto flex max-w-[1400px] flex-wrap items-center gap-3">
        <p className="text-sm font-bold text-on-surface">Compare ({items.length}/4)</p>
        <ul className="flex flex-1 flex-wrap gap-2">
          {items.map((i) => (
            <li key={i.id} className="flex items-center gap-2 rounded-full border-2 border-outline bg-surface-variant py-1 pl-3 pr-1 text-xs font-semibold text-on-surface">
              <span className="max-w-[10rem] truncate">{i.name}</span>
              <button
                type="button"
                onClick={() => remove(i.id)}
                aria-label={`Remove ${i.name} from comparison`}
                className="rounded-full p-1 hover:bg-error/15 hover:text-error"
              >
                <CloseIcon className="h-3 w-3" />
              </button>
            </li>
          ))}
        </ul>
        <button type="button" onClick={clear} className="text-sm font-semibold text-on-surface-muted hover:text-error">
          Clear
        </button>
        <Link
          to="/compare"
          aria-disabled={items.length < 2}
          onClick={(e) => {
            if (items.length < 2) e.preventDefault()
          }}
          className={`press bg-cta-gradient rounded-full border-2 border-ink px-5 py-2 text-sm font-bold text-on-accent shadow-cta ${
            items.length < 2 ? 'opacity-50' : ''
          }`}
        >
          Compare now
        </Link>
      </div>
    </aside>
  )
}
