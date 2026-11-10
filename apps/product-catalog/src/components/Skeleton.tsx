/**
 * Loading skeleton placeholder with glassmorphic design
 */

export function Skeleton() {
  return (
    <div className="glass rounded-xl overflow-hidden h-80 border border-outline/20 shadow-lg animate-pulse">
      <div className="flex flex-col h-full">
        <div className="aspect-square bg-gradient-to-r from-surface-variant via-surface-variant/50 to-surface-variant skeleton-loading" />
        <div className="p-4 flex-1 flex flex-col justify-between">
          <div className="space-y-3">
            <div className="h-4 bg-gradient-to-r from-surface-variant via-surface-variant/50 to-surface-variant skeleton-loading rounded-lg w-3/4" />
            <div className="h-3 bg-gradient-to-r from-surface-variant via-surface-variant/50 to-surface-variant skeleton-loading rounded-lg" />
            <div className="h-3 bg-gradient-to-r from-surface-variant via-surface-variant/50 to-surface-variant skeleton-loading rounded-lg w-5/6" />
          </div>
          <div className="flex justify-between items-end">
            <div className="h-5 bg-gradient-to-r from-surface-variant via-surface-variant/50 to-surface-variant skeleton-loading rounded-lg w-1/3" />
            <div className="h-6 bg-gradient-to-r from-surface-variant via-surface-variant/50 to-surface-variant skeleton-loading rounded-full w-20" />
          </div>
        </div>
      </div>
    </div>
  )
}

export function SkeletonGrid({ count = 20 }: { count?: number }) {
  return (
    <div className="glass rounded-2xl p-6 mb-8 animate-fade-in">
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
        {Array.from({ length: count }).map((_, i) => (
          <div
            key={i}
            style={{
              animation: `slideUpStagger 0.6s ease-out ${i * 0.05}s both`,
            }}
          >
            <Skeleton />
          </div>
        ))}
      </div>
    </div>
  )
}
