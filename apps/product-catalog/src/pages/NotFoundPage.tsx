/**
 * 404 not found page
 */

import { Link } from 'react-router-dom'
import { LogoMark } from '../components/Logo'

export function NotFoundPage() {
  return (
    <div className="flex min-h-[55vh] items-center justify-center">
      <div className="max-w-md space-y-4 text-center">
        <LogoMark className="mx-auto w-14 h-14 text-primary" />
        <p className="font-display text-gradient-brand text-7xl font-extrabold">404</p>
        <h1 className="text-2xl font-bold text-on-surface">This page took a wrong turn</h1>
        <p className="text-on-surface-muted">The page you&apos;re looking for doesn&apos;t exist or has moved.</p>
        <Link
          to="/"
          className="press shine bg-cta-gradient inline-block rounded-xl px-6 py-2.5 font-semibold text-on-accent shadow-cta"
        >
          Back to shopping
        </Link>
      </div>
    </div>
  )
}
