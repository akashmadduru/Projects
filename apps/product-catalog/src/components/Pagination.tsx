/**
 * Pagination controls - clean minimalist design
 */

import { usePaginationStore } from '../stores'

interface Props {
  totalPages: number
  isFirst: boolean
  isLast: boolean
}

export function Pagination({ totalPages, isFirst, isLast }: Props) {
  const page = usePaginationStore((state) => state.page)
  const setPage = usePaginationStore((state) => state.setPage)

  return (
    <div className="flex items-center justify-center gap-4 mt-12 pb-8">
      <button
        onClick={() => setPage(page - 1)}
        disabled={isFirst}
        className="px-4 py-2 bg-on-surface text-surface rounded-lg hover:bg-on-surface/90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium text-sm"
        aria-label="Previous page"
      >
        ← Previous
      </button>

      <span className="text-sm font-medium text-on-surface px-4 py-2 rounded-lg bg-surface-variant border border-outline/10 min-w-max">
        Page <span className="font-bold">{page + 1}</span> of <span className="font-bold">{totalPages}</span>
      </span>

      <button
        onClick={() => setPage(page + 1)}
        disabled={isLast}
        className="px-4 py-2 bg-on-surface text-surface rounded-lg hover:bg-on-surface/90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium text-sm"
        aria-label="Next page"
      >
        Next →
      </button>
    </div>
  )
}
