/**
 * 404 not found page
 */

import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <div className="flex items-center justify-center min-h-[60vh]">
      <div className="text-center">
        <h1 className="text-6xl font-bold text-on-surface mb-2">404</h1>
        <h2 className="text-2xl font-semibold text-on-surface mb-4">Page Not Found</h2>
        <p className="text-on-surface-muted mb-8">The page you&apos;re looking for doesn&apos;t exist.</p>
        <Link
          to="/"
          className="inline-block px-6 py-2 bg-on-surface text-surface rounded-lg hover:opacity-90 transition-opacity"
        >
          Back to Catalog
        </Link>
      </div>
    </div>
  )
}
