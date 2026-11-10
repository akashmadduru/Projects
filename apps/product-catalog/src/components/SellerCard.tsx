/**
 * Seller information card component
 */

interface Props {
  sellerName: string
  sellerRating?: number
  verifiedSeller?: boolean
  responseTime?: string
  positiveRatingPercentage?: number
}

export function SellerCard({ sellerName, sellerRating = 0, verifiedSeller = true, responseTime = 'Usually replies within 24h' }: Props) {
  return (
    <div className="border border-outline/10 rounded-lg p-6 space-y-4 hover:border-outline/20 transition-colors">
      <div className="flex items-center justify-between">
        <h3 className="font-semibold text-on-surface text-lg">Seller Information</h3>
        {verifiedSeller && (
          <div className="flex items-center gap-1 bg-on-surface/5 text-on-surface text-xs font-medium px-2.5 py-1.5 rounded">
            <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 20 20">
              <path fillRule="evenodd" d="M6.267 3.455a3.066 3.066 0 001.745-2.77 3.066 3.066 0 00-3.58 3.53 3.066 3.066 0 001.735-.759zM9.5 2.031a3 3 0 00-5.941 3.856 3 3 0 001.946-3.856zM12.687 5.898a3 3 0 00-3.873 3.873 3 3 0 003.873-3.873zM13.85 12.151a3 3 0 00-2.254 3.949 3 3 0 003.254-2.969 3 3 0 00-.99-1.005z" clipRule="evenodd" />
            </svg>
            Verified Seller
          </div>
        )}
      </div>

      <div className="space-y-2">
        <p className="font-medium text-on-surface">{sellerName}</p>

        <div className="flex items-center gap-2">
          <div className="flex gap-0.5">
            {Array.from({ length: 5 }).map((_, i) => (
              <svg
                key={i}
                className={`w-4 h-4 ${i < Math.floor(sellerRating) ? 'text-on-surface' : 'text-surface-variant'}`}
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path d="M10 15l-5.878 3.09 1.123-6.545L.489 6.91l6.572-.955L10 0l2.939 5.955 6.572.955-4.756 4.635 1.123 6.545z" />
              </svg>
            ))}
          </div>
          <span className="text-sm text-on-surface">{sellerRating.toFixed(1)}/5.0</span>
        </div>
      </div>

      <div className="space-y-2 text-sm">
        <div className="flex items-center gap-2 text-on-surface-muted">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          {responseTime}
        </div>
      </div>

      <button className="w-full py-2.5 text-sm font-medium text-on-surface border border-outline/20 rounded-lg hover:bg-surface-variant transition-colors">
        Contact Seller
      </button>
    </div>
  )
}
