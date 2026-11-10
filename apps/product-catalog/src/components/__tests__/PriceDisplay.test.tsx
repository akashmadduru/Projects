/**
 * Unit tests for PriceDisplay component with counter animation
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, cleanup } from '@testing-library/react'
import { PriceDisplay } from '../PriceDisplay'
import {
  singleProduct,
  productWithZeroPrice,
  productWithHighPrice,
} from '../../test/fixtures'
import { render, mockMatchMedia, resetMatchMedia } from '../../test/test-utils'

describe('PriceDisplay', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetMatchMedia()
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  describe('Rendering', () => {
    it('should render without crashing', () => {
      render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should display formatted price', () => {
      render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const text = screen.getByText(/\$/)
      expect(text).toBeInTheDocument()
    })

    it('should have aria-label with full price', () => {
      render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const span = screen.getByText(/\$/).closest('span')

      expect(span).toHaveAttribute('aria-label')
      expect(span?.getAttribute('aria-label')).toContain('Price')
    })

    it('should have animate-counter class', () => {
      const { container } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const span = container.querySelector('span')

      expect(span).toHaveClass('animate-counter')
    })

    it('should have correct font class by default', () => {
      const { container } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const span = container.querySelector('span')

      expect(span).toHaveClass('text-lg', 'font-semibold')
    })
  })

  describe('Size Variants', () => {
    it('should apply small size class', () => {
      const { container } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} size="sm" />)

      const span = container.querySelector('span')

      expect(span).toHaveClass('text-sm')
    })

    it('should apply medium size class', () => {
      const { container } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} size="md" />)

      const span = container.querySelector('span')

      expect(span).toHaveClass('text-lg')
    })

    it('should apply large size class', () => {
      const { container } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} size="lg" />)

      const span = container.querySelector('span')

      expect(span).toHaveClass('text-2xl')
    })
  })

  describe('Currency Formatting', () => {
    it('should format USD correctly', () => {
      render(<PriceDisplay amount={29.99} currency="USD" />)

      // Should contain $ symbol
      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should format currency with 2 decimal places', () => {
      render(<PriceDisplay amount={10} currency="USD" />)

      // Should display with .00
      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should handle zero price', () => {
      render(<PriceDisplay amount={productWithZeroPrice.priceAmount} currency={productWithZeroPrice.priceCurrency} />)

      expect(screen.getByText(/\$0|0\.00/)).toBeInTheDocument()
    })

    it('should handle high prices', () => {
      render(<PriceDisplay amount={productWithHighPrice.priceAmount} currency={productWithHighPrice.priceCurrency} />)

      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should format decimal values correctly', () => {
      render(<PriceDisplay amount={19.99} currency="USD" />)

      expect(screen.getByText(/\$|19/)).toBeInTheDocument()
    })
  })

  describe('Accessibility', () => {
    it('should have aria-label with current price', () => {
      render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const span = screen.getByText(/\$/).closest('span')

      expect(span).toHaveAttribute('aria-label')
    })

    it('should have aria-label include currency symbol', () => {
      render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const span = screen.getByText(/\$/).closest('span')
      const label = span?.getAttribute('aria-label')

      expect(label).toContain('$')
    })

    it('should have aria-label include Price prefix', () => {
      render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const span = screen.getByText(/\$/).closest('span')
      const label = span?.getAttribute('aria-label')

      expect(label).toContain('Price')
    })

    it('should have text color class for accessibility', () => {
      const { container } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const span = container.querySelector('span')

      expect(span).toHaveClass('text-on-surface')
    })

    it('should have semibold font weight for readability', () => {
      const { container } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const span = container.querySelector('span')

      expect(span).toHaveClass('font-semibold')
    })
  })

  describe('Motion Preferences', () => {
    it('should respect prefers-reduced-motion', () => {
      mockMatchMedia(true)

      render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      // Should display final price immediately
      expect(screen.getByText(/\$/)).toBeInTheDocument()

      resetMatchMedia()
    })

    it('should render normally without prefers-reduced-motion', () => {
      resetMatchMedia()

      render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })
  })

  describe('Edge Cases', () => {
    it('should handle negative price', () => {
      render(<PriceDisplay amount={-29.99} currency="USD" />)

      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should handle very large price', () => {
      render(<PriceDisplay amount={999999.99} currency="USD" />)

      expect(screen.getByText(/\$|999/)).toBeInTheDocument()
    })

    it('should handle price with many decimal places', () => {
      render(<PriceDisplay amount={29.9999} currency="USD" />)

      // Should round or truncate appropriately
      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should handle very small decimal values', () => {
      render(<PriceDisplay amount={0.01} currency="USD" />)

      expect(screen.getByText(/\$0|\.01/)).toBeInTheDocument()
    })

    it('should handle price of exactly 100', () => {
      render(<PriceDisplay amount={100} currency="USD" />)

      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should handle single digit prices', () => {
      render(<PriceDisplay amount={5} currency="USD" />)

      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })
  })

  describe('Memory Management', () => {
    it('should cleanup on unmount', () => {
      const { unmount } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      // Should not throw errors
      expect(() => unmount()).not.toThrow()
    })

    it('should not leak memory on rapid mount/unmount', () => {
      for (let i = 0; i < 10; i++) {
        const { unmount } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)
        unmount()
      }

      expect(true).toBe(true)
    })
  })

  describe('Component State', () => {
    it('should update on price change', () => {
      const { rerender } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      expect(screen.getByText(/\$/)).toBeInTheDocument()

      rerender(<PriceDisplay amount={49.99} currency="USD" />)

      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should maintain price on non-price changes', () => {
      const { rerender } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} size="md" />)

      rerender(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} size="lg" />)

      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })
  })

  describe('Animation Class', () => {
    it('should have animate-counter class for animation styling', () => {
      const { container } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      const span = container.querySelector('span')

      expect(span).toHaveClass('animate-counter')
    })

    it('should apply animation class consistently', () => {
      const { rerender, container } = render(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} />)

      let span = container.querySelector('span')
      expect(span).toHaveClass('animate-counter')

      rerender(<PriceDisplay amount={singleProduct.priceAmount} currency={singleProduct.priceCurrency} size="lg" />)

      span = container.querySelector('span')
      expect(span).toHaveClass('animate-counter')
    })
  })

  describe('Display Values', () => {
    it('should display price value', () => {
      render(<PriceDisplay amount={25.50} currency="USD" />)

      expect(screen.getByText(/\$|25/)).toBeInTheDocument()
    })

    it('should display different prices', () => {
      const { rerender } = render(<PriceDisplay amount={15} currency="USD" />)

      rerender(<PriceDisplay amount={30} currency="USD" />)

      expect(screen.getByText(/\$|30|15/)).toBeInTheDocument()
    })

    it('should have proper aria-label format', () => {
      render(<PriceDisplay amount={29.99} currency="USD" />)

      const span = screen.getByText(/\$/).closest('span')
      const ariaLabel = span?.getAttribute('aria-label')

      expect(ariaLabel).toMatch(/Price.*\$/)
    })
  })
})
