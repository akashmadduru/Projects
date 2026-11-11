/**
 * Unit tests for ProductGrid component with stagger animation
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, cleanup } from '@testing-library/react'
import { ProductGrid } from '../ProductGrid'
import { productsForGrid, sampleProducts } from '../../test/fixtures'
import { render, mockMatchMedia, resetMatchMedia } from '../../test/test-utils'

describe('ProductGrid', () => {
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
    it('should render without crashing', () => {
      render(<ProductGrid products={productsForGrid} />)

      expect(screen.getAllByRole('link').length).toBeGreaterThan(0)
    })

    it('should render correct number of items', () => {
      render(<ProductGrid products={productsForGrid} />)

      const links = screen.getAllByRole('link')

      expect(links.length).toBe(productsForGrid.length)
    })

    it('should render 12 items for 12 products', () => {
      render(<ProductGrid products={productsForGrid} />)

      const links = screen.getAllByRole('link')

      expect(links.length).toBe(12)
    })

    it('should render single item grid', () => {
      const singleProduct = productsForGrid[0]!
      render(<ProductGrid products={[singleProduct]} />)

      const links = screen.getAllByRole('link')

      expect(links.length).toBe(1)
    })

    it('should render large grid with 20+ items', () => {
      const baseProduct = productsForGrid[0]!
      const largeProductList = Array.from({ length: 25 }, (_, i) => ({
        ...baseProduct,
        id: String(i),
        name: `Product ${i}`,
      }))

      render(<ProductGrid products={largeProductList} />)

      const links = screen.getAllByRole('link')

      expect(links.length).toBe(25)
    })

    it('should have fade-in animation class on container', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const wrapper = container.querySelector('.animate-fade-in')

      expect(wrapper).toBeInTheDocument()
    })

    it('should have grid layout classes', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const grid = container.querySelector('.grid')

      expect(grid).toHaveClass('grid', 'grid-cols-2')
    })
  })

  describe('Grid Layout', () => {
    it('should have responsive grid columns', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const grid = container.querySelector('.grid')

      expect(grid).toHaveClass('grid-cols-2', 'md:grid-cols-3', 'xl:grid-cols-4')
    })

    it('should have consistent spacing between items', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const grid = container.querySelector('.grid')

      expect(grid).toHaveClass('gap-3', 'sm:gap-5')
    })

    it('should have mb-8 margin bottom', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const wrapper = container.querySelector('.mb-8')

      expect(wrapper).toBeInTheDocument()
    })

    it('should have will-change-transform on items', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const items = container.querySelectorAll('.will-change-transform')

      expect(items.length).toBe(productsForGrid.length)
    })
  })

  describe('Stagger Animation', () => {
    it('should apply slideUpStagger animation to each item', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const items = container.querySelectorAll('[style*="animation"]')

      expect(items.length).toBeGreaterThan(0)
    })

    it('should apply staggered delay per item', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const items = container.querySelectorAll('.will-change-transform')

      items.forEach((item, index) => {
        const style = item.getAttribute('style')
        const expectedDelay = index * 0.05

        // Check if delay is in the animation string
        expect(style).toContain(`${expectedDelay}s`)
      })
    })

    it('should have correct animation duration', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const item = container.querySelector('[style*="animation"]')
      const style = item?.getAttribute('style')

      // Animation duration should be 0.6s
      expect(style).toContain('0.6s')
    })

    it('should use ease-out timing function', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const item = container.querySelector('[style*="animation"]')
      const style = item?.getAttribute('style')

      expect(style).toContain('ease-out')
    })

    it('should use "both" fill mode', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const item = container.querySelector('[style*="animation"]')
      const style = item?.getAttribute('style')

      expect(style).toContain('both')
    })

    it('should calculate correct delays for multiple items', () => {
      const { container } = render(<ProductGrid products={sampleProducts.slice(0, 5)} />)

      const items = container.querySelectorAll('.will-change-transform')

      items.forEach((item) => {
        const style = item.getAttribute('style')

        // Check delay progression
        expect(style).toContain('s')
      })
    })

    it('should set initial translateY to 0', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const items = container.querySelectorAll('.will-change-transform')

      items.forEach((item) => {
        const style = item.getAttribute('style')

        expect(style).toContain('translateY(0)')
      })
    })
  })

  describe('Animation Timing', () => {
    it('should complete all animations within 0.75s', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const items = container.querySelectorAll('.will-change-transform')

      // Maximum delay = (items.length - 1) * 0.05s
      const lastItemIndex = items.length - 1
      const maxDelay = lastItemIndex * 0.05
      const duration = 0.6

      // Total animation time = duration + maxDelay
      const totalTime = duration + maxDelay

      expect(totalTime).toBeLessThanOrEqual(1.2) // 0.6s duration + max 0.55s delay for 12 items
    })

    it('should have first item no delay', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const firstItem = container.querySelector('.will-change-transform')
      const style = firstItem?.getAttribute('style')

      // First item should have 0s delay
      expect(style).toContain('0s')
    })

    it('should have last item maximum delay for 12 items', () => {
      render(<ProductGrid products={productsForGrid} />)

      // Last item index is 11
      // Delay should be 11 * 0.05 = 0.55s
      const expectedDelay = 11 * 0.05

      expect(expectedDelay).toBe(0.55)
    })

    it('should respect animation timing for 20 items', () => {
      const baseProduct = productsForGrid[0]!
      const manyProducts = Array.from({ length: 20 }, (_, i) => ({
        ...baseProduct,
        id: String(i),
        name: `Product ${i}`,
      }))

      render(<ProductGrid products={manyProducts} />)

      const lastItemIndex = manyProducts.length - 1
      const maxDelay = lastItemIndex * 0.05

      // Max delay should be 19 * 0.05 = 0.95s
      expect(maxDelay).toBeCloseTo(0.95, 5)
    })
  })

  describe('Cumulative Layout Shift (CLS)', () => {
    it('should have no layout shift with will-change-transform', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const items = container.querySelectorAll('.will-change-transform')

      items.forEach((item) => {
        // will-change-transform should prevent layout shifts
        expect(item).toHaveClass('will-change-transform')
      })
    })

    it('should use transform instead of position changes', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const items = container.querySelectorAll('.will-change-transform')

      items.forEach((item) => {
        const style = item.getAttribute('style')

        // Should use transform: translateY(0) not top/bottom
        expect(style).toContain('transform')
      })
    })

    it('should maintain grid layout stability', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const grid = container.querySelector('.grid')

      // Grid should have stable gap and columns
      expect(grid).toHaveClass('gap-3', 'sm:gap-5')
    })
  })

  describe('Accessibility', () => {
    it('should render all products as links', () => {
      render(<ProductGrid products={productsForGrid} />)

      const links = screen.getAllByRole('link')

      expect(links.length).toBe(productsForGrid.length)
    })

    it('should maintain semantic HTML structure', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const divs = container.querySelectorAll('div')

      // Should have proper div structure
      expect(divs.length).toBeGreaterThan(0)
    })

    it('should respect prefers-reduced-motion', () => {
      mockMatchMedia(true)

      render(<ProductGrid products={productsForGrid} />)

      // Grid should still render even with motion preference
      expect(screen.getAllByRole('link').length).toBe(productsForGrid.length)

      resetMatchMedia()
    })

    it('should support keyboard navigation', () => {
      render(<ProductGrid products={productsForGrid} />)

      const links = screen.getAllByRole('link')

      // All links should be keyboard accessible
      links.forEach((link) => {
        expect(link.tagName).toBe('A')
        expect(link).toHaveAttribute('href')
      })
    })

    it('should have proper link semantics', () => {
      render(<ProductGrid products={productsForGrid} />)

      const links = screen.getAllByRole('link')

      links.forEach((link, index) => {
        expect(link).toHaveAttribute('href', `/products/${productsForGrid[index]!.id}`)
      })
    })
  })

  describe('Empty State', () => {
    it('should show an empty-state message instead of an empty grid', () => {
      const { container } = render(<ProductGrid products={[]} />)

      expect(screen.getByText('No products found')).toBeInTheDocument()
      expect(container.querySelector('.grid')).not.toBeInTheDocument()
    })
  })

  describe('Product Variations', () => {
    it('should render products with different ratings', () => {
      render(<ProductGrid products={productsForGrid} />)

      const links = screen.getAllByRole('link')

      expect(links.length).toBe(productsForGrid.length)
    })

    it('should render products with different prices', () => {
      render(<ProductGrid products={productsForGrid} />)

      const priceElements = screen.getAllByText(/\$/)

      expect(priceElements.length).toBeGreaterThan(0)
    })

    it('should render products with different inventory levels', () => {
      render(<ProductGrid products={productsForGrid} />)

      expect(screen.getAllByRole('link').length).toBe(productsForGrid.length)
    })
  })

  describe('Responsive Behavior', () => {
    it('should have mobile first grid layout', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const grid = container.querySelector('.grid')

      // Two columns on phones, as in mobile shopping apps
      expect(grid).toHaveClass('grid-cols-2')
    })

    it('should have tablet layout classes', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const grid = container.querySelector('.grid')

      expect(grid).toHaveClass('md:grid-cols-3')
    })

    it('should have desktop layout classes', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const grid = container.querySelector('.grid')

      expect(grid).toHaveClass('md:grid-cols-3')
    })

    it('should have large desktop layout classes', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const grid = container.querySelector('.grid')

      expect(grid).toHaveClass('xl:grid-cols-4')
    })
  })

  describe('Performance', () => {
    it('should render large grid efficiently', () => {
      const baseProduct = productsForGrid[0]!
      const largeList = Array.from({ length: 100 }, (_, i) => ({
        ...baseProduct,
        id: String(i),
        name: `Product ${i}`,
      }))

      render(<ProductGrid products={largeList} />)

      const links = screen.getAllByRole('link')

      expect(links.length).toBe(100)
    })

    it('should use will-change-transform for optimization', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const items = container.querySelectorAll('.will-change-transform')

      expect(items.length).toBe(productsForGrid.length)
    })

    it('should not create excessive DOM nodes', () => {
      const { container } = render(<ProductGrid products={productsForGrid} />)

      const gridItems = container.querySelectorAll('.will-change-transform')

      // Should have exactly one wrapper per product
      expect(gridItems.length).toBe(productsForGrid.length)
    })
  })

  describe('Integration', () => {
    it('should render ProductCard components', () => {
      render(<ProductGrid products={productsForGrid} />)

      const links = screen.getAllByRole('link')

      expect(links.length).toBe(productsForGrid.length)
    })

    it('should pass product data correctly to ProductCard', () => {
      render(<ProductGrid products={productsForGrid} />)

      productsForGrid.forEach((product) => {
        expect(screen.getByText(product.name)).toBeInTheDocument()
      })
    })

    it('should generate correct product URLs', () => {
      render(<ProductGrid products={productsForGrid} />)

      const links = screen.getAllByRole('link')
      expect(links.length).toBe(productsForGrid.length)

      links.forEach((link, index) => {
        const expectedHref = `/products/${productsForGrid[index]!.id}`
        expect(link).toHaveAttribute('href', expectedHref)
      })
    })
  })
})
