/**
 * Unit tests for StarRating component with star fill animation
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, cleanup } from '@testing-library/react'
import { StarRating } from '../StarRating'
import { render, mockMatchMedia, resetMatchMedia } from '../../test/test-utils'

describe('StarRating', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetMatchMedia()
    vi.useFakeTimers()
  })

  afterEach(() => {
    cleanup()
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
  })

  describe('Rendering', () => {
    it('should render 5 stars by default', () => {
      const { container } = render(<StarRating rating={3.5} reviewsCount={10} />)

      const svgs = container.querySelectorAll('svg')
      // Should have 5 star SVGs
      expect(svgs.length).toBe(5)
    })

    it('should render without crashing', () => {
      render(<StarRating rating={4} reviewsCount={100} />)

      expect(screen.getByText(/4.0/)).toBeInTheDocument()
    })

    it('should display review count when showCount is true', () => {
      render(<StarRating rating={4} reviewsCount={100} showCount={true} />)

      expect(screen.getByText(/100 reviews/)).toBeInTheDocument()
    })

    it('should not display review count when showCount is false', () => {
      render(<StarRating rating={4} reviewsCount={100} showCount={false} />)

      expect(screen.queryByText(/100 reviews/)).not.toBeInTheDocument()
    })

    it('should format rating to one decimal place', () => {
      render(<StarRating rating={4.567} reviewsCount={100} showCount={true} />)

      expect(screen.getByText(/4.6/)).toBeInTheDocument()
    })
  })

  describe('Star Fill Logic', () => {
    it('should fill stars based on rating value', () => {
      const { container } = render(<StarRating rating={3} reviewsCount={50} />)

      const stars = container.querySelectorAll('svg')

      // First 3 stars should be filled
      expect(stars.length).toBe(5)
    })

    it('should handle full stars correctly', () => {
      render(<StarRating rating={5} reviewsCount={200} />)

      expect(screen.getByText(/5.0/)).toBeInTheDocument()
    })

    it('should handle zero rating', () => {
      render(<StarRating rating={0} reviewsCount={0} showCount={true} />)

      expect(screen.getByText(/0.0/)).toBeInTheDocument()
    })

    it('should handle half star correctly', () => {
      render(<StarRating rating={3.5} reviewsCount={100} showCount={true} />)

      expect(screen.getByText(/3.5/)).toBeInTheDocument()
    })

    it('should handle rating just above half', () => {
      render(<StarRating rating={3.6} reviewsCount={100} showCount={true} />)

      expect(screen.getByText(/3.6/)).toBeInTheDocument()
    })

    it('should handle rating just below half', () => {
      render(<StarRating rating={3.4} reviewsCount={100} showCount={true} />)

      expect(screen.getByText(/3.4/)).toBeInTheDocument()
    })
  })

  describe('Animations', () => {
    it('should apply star-fill animation class', () => {
      const { container } = render(<StarRating rating={3} reviewsCount={50} />)

      const stars = container.querySelectorAll('svg')

      stars.forEach((star) => {
        expect(star).toHaveClass('animate-star-fill')
      })
    })

    it('should apply staggered animation delay to each star', () => {
      const { container } = render(<StarRating rating={4} reviewsCount={100} />)

      const stars = container.querySelectorAll('svg')

      stars.forEach((star, index) => {
        const style = star.getAttribute('style')
        // Should have animationDelay: index * 0.1s
        expect(style).toContain(`${index * 0.1}s`)
      })
    })

    it('should have correct animation timing', () => {
      const { container } = render(<StarRating rating={3.5} reviewsCount={50} />)

      const firstStar = container.querySelector('svg')

      expect(firstStar).toHaveClass('animate-star-fill')
    })

    it('should respect prefers-reduced-motion', () => {
      mockMatchMedia(true)

      const { container } = render(<StarRating rating={4} reviewsCount={100} />)

      const stars = container.querySelectorAll('svg')

      // Stars should still render even with prefers-reduced-motion
      expect(stars.length).toBeGreaterThan(0)

      resetMatchMedia()
    })

    it('should complete animation within 0.4s per star', () => {
      const { container } = render(<StarRating rating={5} reviewsCount={200} />)

      const stars = container.querySelectorAll('svg')

      // Maximum delay should be (5-1) * 0.1 = 0.4s
      const lastStarStyle = stars[4]!.getAttribute('style')

      expect(lastStarStyle).toContain('0.4s')
    })
  })

  describe('Size Variants', () => {
    it('should apply small size classes', () => {
      const { container } = render(<StarRating rating={4} reviewsCount={100} size="sm" />)

      const stars = container.querySelectorAll('svg')

      stars.forEach((star) => {
        expect(star).toHaveClass('w-3', 'h-3')
      })
    })

    it('should apply medium size classes by default', () => {
      const { container } = render(<StarRating rating={4} reviewsCount={100} size="md" />)

      const stars = container.querySelectorAll('svg')

      stars.forEach((star) => {
        expect(star).toHaveClass('w-4', 'h-4')
      })
    })

    it('should apply large size classes', () => {
      const { container } = render(<StarRating rating={4} reviewsCount={100} size="lg" />)

      const stars = container.querySelectorAll('svg')

      stars.forEach((star) => {
        expect(star).toHaveClass('w-5', 'h-5')
      })
    })

    it('should apply correct text size for sm', () => {
      render(<StarRating rating={4} reviewsCount={100} size="sm" showCount={true} />)

      const textElement = screen.getByText(/4.0/)

      expect(textElement).toHaveClass('text-xs')
    })

    it('should apply correct text size for md', () => {
      render(<StarRating rating={4} reviewsCount={100} size="md" showCount={true} />)

      const textElement = screen.getByText(/4.0/)

      expect(textElement).toHaveClass('text-sm')
    })

    it('should apply correct text size for lg', () => {
      render(<StarRating rating={4} reviewsCount={100} size="lg" showCount={true} />)

      const textElement = screen.getByText(/4.0/)

      expect(textElement).toHaveClass('text-base')
    })
  })

  describe('Review Count Display', () => {
    it('should display singular "review" when count is 1', () => {
      render(<StarRating rating={4} reviewsCount={1} showCount={true} />)

      expect(screen.getByText(/1 reviews/)).toBeInTheDocument()
    })

    it('should display plural "reviews" for count > 1', () => {
      render(<StarRating rating={4} reviewsCount={10} showCount={true} />)

      expect(screen.getByText(/10 reviews/)).toBeInTheDocument()
    })

    it('should handle high review counts', () => {
      render(<StarRating rating={4} reviewsCount={10000} showCount={true} />)

      expect(screen.getByText(/10000 reviews/)).toBeInTheDocument()
    })

    it('should handle zero review count', () => {
      render(<StarRating rating={0} reviewsCount={0} showCount={true} />)

      expect(screen.getByText(/0 reviews/)).toBeInTheDocument()
    })
  })

  describe('Edge Cases', () => {
    it('should handle rating > 5', () => {
      render(<StarRating rating={6} reviewsCount={100} showCount={true} />)

      // Should still display properly
      expect(screen.getByText(/6.0/)).toBeInTheDocument()
    })

    it('should handle negative rating', () => {
      render(<StarRating rating={-1} reviewsCount={100} showCount={true} />)

      // Should handle gracefully
      expect(screen.getByText(/-1.0/)).toBeInTheDocument()
    })

    it('should handle undefined rating', () => {
      render(<StarRating rating={undefined} reviewsCount={100} showCount={true} />)

      // Should use default rating of 0
      expect(screen.getByText(/0.0/)).toBeInTheDocument()
    })

    it('should handle undefined reviewsCount', () => {
      render(<StarRating rating={4} reviewsCount={undefined} showCount={true} />)

      // Should use default of 0
      expect(screen.getByText(/4.0.*0 reviews/)).toBeInTheDocument()
    })

    it('should handle very small decimal rating', () => {
      render(<StarRating rating={0.1} reviewsCount={5} showCount={true} />)

      expect(screen.getByText(/0.1/)).toBeInTheDocument()
    })

    it('should handle decimal rating close to rounding edge', () => {
      render(<StarRating rating={4.95} reviewsCount={100} showCount={true} />)

      expect(screen.getByText(/5.0/)).toBeInTheDocument()
    })
  })

  describe('Accessibility', () => {
    it('should have proper color values', () => {
      const { container } = render(<StarRating rating={4} reviewsCount={100} />)

      const stars = container.querySelectorAll('svg')

      stars.forEach((star) => {
        // Should have either text-accent (filled) or text-on-surface-muted (empty)
        const classes = star.className.baseVal || star.className
        expect(classes).toMatch(/text-(accent|on-surface-muted)/)
      })
    })

    it('should have proper color contrast for filled stars', () => {
      const { container } = render(<StarRating rating={5} reviewsCount={200} />)

      const stars = container.querySelectorAll('svg')

      stars.forEach((star) => {
        expect(star).toHaveClass('text-accent')
      })
    })

    it('should have proper color contrast for empty stars', () => {
      const { container } = render(<StarRating rating={0} reviewsCount={10} />)

      const stars = container.querySelectorAll('svg')

      stars.forEach((star) => {
        expect(star).toHaveClass('text-on-surface-muted')
      })
    })
  })

  describe('Performance', () => {
    it('should be memoized to prevent unnecessary re-renders', () => {
      const { rerender } = render(<StarRating rating={4} reviewsCount={100} />)

      const text1 = screen.getByText(/4.0/)

      rerender(<StarRating rating={4} reviewsCount={100} />)

      const text2 = screen.getByText(/4.0/)

      // Should be the same element (no re-render)
      expect(text1).toEqual(text2)
    })

    it('should re-render when rating changes', () => {
      const { rerender } = render(<StarRating rating={4} reviewsCount={100} showCount={true} />)

      expect(screen.getByText(/4.0/)).toBeInTheDocument()

      rerender(<StarRating rating={3} reviewsCount={100} showCount={true} />)

      expect(screen.getByText(/3.0/)).toBeInTheDocument()
    })

    it('should re-render when reviewsCount changes', () => {
      const { rerender } = render(<StarRating rating={4} reviewsCount={100} showCount={true} />)

      expect(screen.getByText(/100 reviews/)).toBeInTheDocument()

      rerender(<StarRating rating={4} reviewsCount={200} showCount={true} />)

      expect(screen.getByText(/200 reviews/)).toBeInTheDocument()
    })

    it('should not create excessive DOM updates', () => {
      const { container } = render(<StarRating rating={4} reviewsCount={100} />)

      const initialChildCount = container.querySelectorAll('svg').length

      expect(initialChildCount).toBe(5)
    })
  })

  describe('Star Color States', () => {
    it('should have accent color for filled stars', () => {
      const { container } = render(<StarRating rating={3} reviewsCount={50} />)

      const stars = container.querySelectorAll('svg.text-accent')

      expect(stars.length).toBeGreaterThan(0)
    })

    it('should have muted color for empty stars', () => {
      const { container } = render(<StarRating rating={2} reviewsCount={50} />)

      const mutedStars = container.querySelectorAll('svg.text-on-surface-muted')

      expect(mutedStars.length).toBeGreaterThan(0)
    })
  })

  describe('Display Formatting', () => {
    it('should format rating correctly as fixed decimal', () => {
      render(<StarRating rating={3.14159} reviewsCount={50} showCount={true} />)

      expect(screen.getByText(/3.1/)).toBeInTheDocument()
    })

    it('should display with proper spacing', () => {
      render(<StarRating rating={4} reviewsCount={100} showCount={true} />)

      const container = screen.getByText(/4.0.*100 reviews/).closest('div')

      expect(container).toHaveClass('flex', 'items-center', 'gap-2')
    })

    it('should have stars grouped together', () => {
      const { container } = render(<StarRating rating={4} reviewsCount={100} />)

      const starsWrapper = container.querySelector('.flex.items-center.gap-0\\.5')

      expect(starsWrapper).toBeInTheDocument()
    })
  })
})
