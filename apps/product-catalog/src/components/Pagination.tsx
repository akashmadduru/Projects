/**
 * Pagination controls - numbered page range with Previous/Next and an item count below
 */

interface Props {
  /** Zero-based current page. */
  page: number
  pageSize: number
  totalPages: number
  totalElements: number
  onPageChange: (page: number) => void
}

export type PageRangeItem = number | 'ellipsis'

/**
 * Zero-based page indexes to render: always the first and last page, the current page with
 * `siblings` on each side, and an ellipsis wherever a gap of two or more pages is hidden.
 */
export function getPageRange(current: number, totalPages: number, siblings = 1): PageRangeItem[] {
  // first + last + current + 2*siblings + 2 ellipses: below this, every page fits.
  const maxSlots = 2 * siblings + 5
  if (totalPages <= maxSlots) {
    return Array.from({ length: totalPages }, (_, i) => i)
  }

  const last = totalPages - 1
  const start = Math.max(1, Math.min(current - siblings, last - 1 - 2 * siblings - 1))
  const end = Math.min(last - 1, Math.max(current + siblings, 2 * siblings + 2))

  // An ellipsis standing in for a single page is worse than showing that page.
  const range: PageRangeItem[] = [0]
  if (start === 2) range.push(1)
  else if (start > 2) range.push('ellipsis')
  for (let i = start; i <= end; i++) range.push(i)
  if (end === last - 2) range.push(last - 1)
  else if (end < last - 2) range.push('ellipsis')
  range.push(last)
  return range
}

const navButton =
  'press px-3 sm:px-4 py-2 bg-brand-gradient text-on-brand rounded-full border-2 border-ink shadow-cta hover:-translate-y-0.5 hover:shadow-card-hover disabled:hover:translate-y-0 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-outline focus-visible:ring-offset-2'

export function Pagination({ page: requestedPage, pageSize: size, totalPages, totalElements, onPageChange }: Props) {
  const lastPage = Math.max(totalPages - 1, 0)
  const page = Math.min(requestedPage, lastPage)
  const firstItem = totalElements === 0 ? 0 : page * size + 1
  const lastItem = Math.min((page + 1) * size, totalElements)

  const goTo = (target: number) => {
    const next = Math.min(Math.max(target, 0), lastPage)
    if (next === page) return
    onPageChange(next)
    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' })
  }

  return (
    <nav aria-label="Pagination" className="mt-12 pb-8 flex flex-col items-center gap-4">
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 flex-wrap">
          <button
            type="button"
            onClick={() => goTo(page - 1)}
            disabled={page === 0}
            className={navButton}
            aria-label="Previous page"
          >
            <span aria-hidden="true">←</span>
            <span className="hidden sm:inline"> Previous</span>
          </button>

          {/* Full range from sm up; a compact "x of y" keeps phone widths on one line. */}
          <ol className="hidden sm:flex items-center gap-1">
            {getPageRange(page, totalPages).map((item, i) =>
              item === 'ellipsis' ? (
                <li key={`ellipsis-${i}`} className="px-2 text-sm text-on-surface-muted" aria-hidden="true">
                  …
                </li>
              ) : (
                <li key={item}>
                  <button
                    type="button"
                    onClick={() => goTo(item)}
                    aria-label={`Page ${item + 1}`}
                    aria-current={item === page ? 'page' : undefined}
                    className={`press min-w-10 h-10 px-3 rounded-full border-2 text-sm font-bold transition-all duration-500 focus:outline-none focus-visible:ring-2 focus-visible:ring-outline focus-visible:ring-offset-2 ${
                      item === page
                        ? 'bg-cta-gradient border-ink text-on-accent shadow-cta scale-110'
                        : 'text-on-surface border-outline bg-surface hover:-translate-y-0.5 hover:border-primary/50 hover:text-primary'
                    }`}
                  >
                    {item + 1}
                  </button>
                </li>
              ),
            )}
          </ol>
          <span className="sm:hidden text-sm font-medium text-on-surface px-3 py-2">
            {page + 1} / {totalPages}
          </span>

          <button
            type="button"
            onClick={() => goTo(page + 1)}
            disabled={page >= lastPage}
            className={navButton}
            aria-label="Next page"
          >
            <span className="hidden sm:inline">Next </span>
            <span aria-hidden="true">→</span>
          </button>
        </div>
      )}

      <p className="text-sm text-on-surface-muted" aria-live="polite">
        {totalElements === 0 ? (
          'No products'
        ) : (
          <>
            Showing <span className="font-semibold text-on-surface">{firstItem}</span>–
            <span className="font-semibold text-on-surface">{lastItem}</span> of{' '}
            <span className="font-semibold text-on-surface">{totalElements}</span> products
          </>
        )}
      </p>
    </nav>
  )
}
