import { describe, it, expect } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, Link } from 'react-router-dom'
import { ProductImage } from '../ProductImage'

const SRC = 'https://images.unsplash.com/photo-1?w=400'

describe('ProductImage', () => {
  it('renders a themed placeholder when there is no src', () => {
    render(<ProductImage src={null} alt="Widget" productId="p1" />)

    const placeholder = screen.getByRole('img', { name: 'Widget' })
    expect(placeholder.tagName).toBe('DIV')
    expect(placeholder.style.background).toContain('hsl(var(--color-')
  })

  it('reveals the image once it has loaded', () => {
    render(<ProductImage src={SRC} alt="Widget" productId="p1" />)
    const img = screen.getByAltText('Widget')

    expect(img).toHaveClass('opacity-0')
    fireEvent.load(img)
    expect(img).toHaveClass('opacity-100')
  })

  it('retries with a cache-busting param that preserves the existing query string', () => {
    render(<ProductImage src={SRC} alt="Widget" productId="p1" />)
    fireEvent.error(screen.getByAltText('Widget'))

    fireEvent.click(screen.getByRole('button', { name: 'Retry loading image' }))

    const src = new URL(screen.getByAltText('Widget').getAttribute('src')!)
    expect(src.searchParams.get('w')).toBe('400')
    expect(src.searchParams.get('retry')).toBe('1')
  })

  it('does not follow the surrounding card link when Retry is clicked', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route
            path="/"
            element={
              <Link to="/products/p1">
                <ProductImage src={SRC} alt="Widget" productId="p1" />
              </Link>
            }
          />
          <Route path="/products/p1" element={<p>detail page</p>} />
        </Routes>
      </MemoryRouter>,
    )
    fireEvent.error(screen.getByAltText('Widget'))

    fireEvent.click(screen.getByRole('button', { name: 'Retry loading image' }))

    expect(screen.queryByText('detail page')).not.toBeInTheDocument()
  })

  it('resets to loading when the src changes', () => {
    const { rerender } = render(<ProductImage src={SRC} alt="Widget" productId="p1" />)
    fireEvent.error(screen.getByAltText('Widget'))

    rerender(<ProductImage src="https://img.example/other.png" alt="Widget" productId="p1" />)

    expect(screen.getByAltText('Widget')).toHaveAttribute('src', 'https://img.example/other.png')
  })
})
