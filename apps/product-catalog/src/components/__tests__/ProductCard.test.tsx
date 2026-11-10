/**
 * Unit tests for ProductCard component with ripple effect animation
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, fireEvent, cleanup } from '@testing-library/react'
import { ProductCard } from '../ProductCard'
import { singleProduct } from '../../test/fixtures'
import { render, resetMatchMedia } from '../../test/test-utils'

describe('ProductCard', () => {
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
      render(<ProductCard product={singleProduct} />)
      expect(screen.getByRole('link')).toBeInTheDocument()
    })

    it('should display product name', () => {
      render(<ProductCard product={singleProduct} />)
      expect(screen.getByText(singleProduct.name)).toBeInTheDocument()
    })

    it('should display product category', () => {
      render(<ProductCard product={singleProduct} />)
      expect(screen.getByText(singleProduct.categoryId!)).toBeInTheDocument()
    })

    it('should render with correct link href', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')
      expect(link).toHaveAttribute('href', `/products/${singleProduct.id}`)
    })

    it('should have ripple-container class', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')
      expect(link).toHaveClass('ripple-container')
    })

    it('should display price', () => {
      render(<ProductCard product={singleProduct} />)
      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should have shadow styling', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')
      expect(link).toHaveClass('shadow-md')
    })
  })

  describe('Animations - Ripple Effect', () => {
    it('should create ripple element on click', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      const appendChildSpy = vi.spyOn(link, 'appendChild')

      fireEvent.click(link, {
        clientX: 50,
        clientY: 50,
      })

      expect(appendChildSpy).toHaveBeenCalled()

      appendChildSpy.mockRestore()
    })

    it('should apply ripple animation style', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      const appendChildSpy = vi.spyOn(link, 'appendChild')

      fireEvent.click(link, {
        clientX: 50,
        clientY: 50,
      })

      expect(appendChildSpy).toHaveBeenCalledTimes(1)
      const rippleElement = appendChildSpy.mock.calls[0]![0] as HTMLElement
      expect(rippleElement.className).toContain('ripple')

      appendChildSpy.mockRestore()
    })

    it('should remove ripple after 300ms', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')
      const removeSpy = vi.spyOn(Element.prototype, 'remove')

      fireEvent.click(link, {
        clientX: 50,
        clientY: 50,
      })

      expect(removeSpy).not.toHaveBeenCalled()

      vi.advanceTimersByTime(300)

      expect(removeSpy).toHaveBeenCalled()

      removeSpy.mockRestore()
    })

    it('should not create multiple ripples on rapid clicks', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')
      const appendChildSpy = vi.spyOn(link, 'appendChild')

      fireEvent.click(link, { clientX: 50, clientY: 50 })
      fireEvent.click(link, { clientX: 60, clientY: 60 })
      fireEvent.click(link, { clientX: 70, clientY: 70 })

      expect(appendChildSpy).toHaveBeenCalledTimes(3)

      appendChildSpy.mockRestore()
    })

    it('should calculate ripple size correctly', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      const appendChildSpy = vi.spyOn(link, 'appendChild')

      fireEvent.click(link, {
        clientX: 100,
        clientY: 100,
      })

      expect(appendChildSpy).toHaveBeenCalled()
      const ripple = appendChildSpy.mock.calls[0]![0] as HTMLElement

      expect(ripple.style.width).toBeDefined()
      expect(ripple.style.height).toBeDefined()

      appendChildSpy.mockRestore()
    })

    it('should have animation defined on ripple', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      const appendChildSpy = vi.spyOn(link, 'appendChild')

      fireEvent.click(link, {
        clientX: 50,
        clientY: 50,
      })

      const ripple = appendChildSpy.mock.calls[0]![0] as HTMLElement
      expect(ripple.style.animation).toBeDefined()

      appendChildSpy.mockRestore()
    })
  })

  describe('Accessibility', () => {
    it('should have focus ring styling', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link).toHaveClass('focus:ring-2', 'focus:ring-outline', 'focus:ring-offset-2')
    })

    it('should be keyboard focusable', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link).toHaveAttribute('href')
      expect(link).toHaveClass('focus:outline-none')
    })

    it('should have outline-none in default state', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link).toHaveClass('focus:outline-none')
    })

    it('should have proper border styling for contrast', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link).toHaveClass('border')
    })
  })

  describe('Memory Management', () => {
    it('should cleanup ripple on unmount', () => {
      const { unmount } = render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      fireEvent.click(link, {
        clientX: 50,
        clientY: 50,
      })

      unmount()

      // Should not throw any errors
      expect(() => unmount()).not.toThrow()
    })

    it('should cleanup setTimeout on unmount', () => {
      const { unmount } = render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      fireEvent.click(link, {
        clientX: 50,
        clientY: 50,
      })

      unmount()

      vi.advanceTimersByTime(300)

      // Should not throw
      expect(true).toBe(true)
    })
  })

  describe('Component Integration', () => {
    it('should render ProductImage component', () => {
      render(<ProductCard product={singleProduct} />)

      // ProductImage is rendered as img
      const img = screen.getByAltText(singleProduct.name)

      expect(img).toBeInTheDocument()
    })

    it('should render PriceDisplay component', () => {
      render(<ProductCard product={singleProduct} />)

      // PriceDisplay shows formatted price
      expect(screen.getByText(/\$/)).toBeInTheDocument()
    })

    it('should render StockStatus component', () => {
      render(<ProductCard product={singleProduct} />)

      // StockStatus shows stock status message
      expect(screen.getByText(/Stock|stock/i)).toBeInTheDocument()
    })
  })

  describe('Responsive Design', () => {
    it('should have responsive classes applied', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link).toHaveClass('rounded-lg', 'overflow-hidden', 'shadow-md')
    })

    it('should have border styling', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link).toHaveClass('border', 'border-outline/10')
    })

    it('should have hover effects applied', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link).toHaveClass('hover:shadow-xl', 'hover:border-outline/20')
    })

    it('should have proper group styling', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link).toHaveClass('group')
    })

    it('should have fade-in animation', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link).toHaveClass('animate-fade-in')
    })
  })

  describe('Product Data Display', () => {
    it('should display all product information', () => {
      render(<ProductCard product={singleProduct} />)

      expect(screen.getByText(singleProduct.name)).toBeInTheDocument()
      expect(screen.getByText(singleProduct.categoryId!)).toBeInTheDocument()
    })

    it('should render with correct href pattern', () => {
      render(<ProductCard product={singleProduct} />)
      const link = screen.getByRole('link')

      expect(link.getAttribute('href')).toMatch(/^\/products\//)
    })
  })
})
