/**
 * Category filter dropdown component
 */

import { useState, useRef, useEffect } from 'react'
import type { Category } from '../types'

interface Props {
  categories: Category[]
  selectedCategory?: string
  onCategoryChange: (categoryId: string | undefined) => void
}

export function CategoryFilter({ categories, selectedCategory, onCategoryChange }: Props) {
  const [isOpen, setIsOpen] = useState(false)
  const dropdownRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false)
      }
    }

    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const selectedLabel = selectedCategory
    ? categories.find((c) => c.id === selectedCategory)?.name
    : 'All Categories'

  return (
    <div ref={dropdownRef} className="relative">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-2 px-4 py-2 border border-outline/20 rounded-lg text-on-surface hover:border-outline/40 transition-colors text-sm font-medium"
      >
        {selectedLabel}
        <svg
          className={`w-4 h-4 transition-transform ${isOpen ? 'rotate-180' : ''}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 14l-7 7m0 0l-7-7m7 7V3" />
        </svg>
      </button>

      {isOpen && (
        <div className="absolute top-full left-0 right-0 mt-2 bg-surface border border-outline/10 rounded-lg shadow-lg z-10 animate-fade-in overflow-hidden">
          <button
            onClick={() => {
              onCategoryChange(undefined)
              setIsOpen(false)
            }}
            className={`w-full text-left px-4 py-2.5 text-sm transition-colors ${
              !selectedCategory
                ? 'bg-surface-variant text-on-surface font-medium'
                : 'text-on-surface hover:bg-surface-variant'
            }`}
          >
            All Categories
          </button>

          <div className="border-t border-outline/5" />

          {categories.map((category) => (
            <button
              key={category.id}
              onClick={() => {
                onCategoryChange(category.id)
                setIsOpen(false)
              }}
              className={`w-full text-left px-4 py-2.5 text-sm transition-colors ${
                selectedCategory === category.id
                  ? 'bg-surface-variant text-on-surface font-medium'
                  : 'text-on-surface hover:bg-surface-variant'
              }`}
            >
              {category.name}
              {category.description && (
                <div className="text-xs text-on-surface-muted mt-0.5">{category.description}</div>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
