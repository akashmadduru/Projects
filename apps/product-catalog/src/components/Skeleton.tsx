/**
 * Loading placeholders shaped like ProductCard
 */

export function Skeleton() {
  return (
    <div className="overflow-hidden rounded-xl border border-outline bg-surface shadow-card" aria-hidden="true">
      <div className="aspect-square skeleton-loading" />
      <div className="space-y-3 p-4">
        <div className="h-4 w-11/12 rounded skeleton-loading" />
        <div className="h-4 w-2/3 rounded skeleton-loading" />
        <div className="h-4 w-16 rounded skeleton-loading" />
        <div className="h-6 w-1/2 rounded skeleton-loading" />
        <div className="h-9 w-full rounded-lg skeleton-loading" />
      </div>
    </div>
  )
}

export function SkeletonGrid({ count = 20 }: { count?: number }) {
  return (
    <div role="status" aria-label="Loading products" className="grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-3 xl:grid-cols-4">
      {Array.from({ length: count }).map((_, i) => (
        <Skeleton key={i} />
      ))}
    </div>
  )
}
