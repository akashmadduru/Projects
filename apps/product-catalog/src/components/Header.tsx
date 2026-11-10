/**
 * Header component - clean minimalist navbar
 */

import { ThemeToggle } from './ThemeToggle'

export function Header() {
  return (
    <header className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md border-b border-outline/10">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16">
          <div className="flex-shrink-0">
            <h1 className="text-xl font-semibold text-on-surface hover:text-on-surface/80 transition-colors duration-200">
              Product Catalog
            </h1>
          </div>
          <nav className="ml-auto">
            <ThemeToggle />
          </nav>
        </div>
      </div>
    </header>
  )
}
