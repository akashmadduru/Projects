import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ActiveFilters } from '../ActiveFilters'
import { FilterPanel } from '../FilterPanel'
import type { ProductQuery } from '../../types'

const panel = (query: ProductQuery, onChange = vi.fn()) => {
  render(<FilterPanel query={query} categories={[]} onChange={onChange} />)
  return onChange
}

describe('FilterPanel switches', () => {
  it('shows each switch off by default', () => {
    panel({})

    for (const name of ['In stock only', 'On sale', 'Top picks', 'Verified sellers']) {
      expect(screen.getByRole('switch', { name: new RegExp(name) })).toHaveAttribute('aria-checked', 'false')
    }
  })

  it('reflects the query and turns a switch on with true', () => {
    const onChange = panel({ featured: true })

    expect(screen.getByRole('switch', { name: /Top picks/ })).toHaveAttribute('aria-checked', 'true')

    fireEvent.click(screen.getByRole('switch', { name: /In stock only/ }))
    expect(onChange).toHaveBeenCalledWith({ inStock: true })
  })

  it('turns a switch off with undefined, never false', () => {
    const onChange = panel({ onSale: true })

    fireEvent.click(screen.getByRole('switch', { name: /On sale/ }))

    expect(onChange).toHaveBeenCalledWith({ onSale: undefined })
  })
})

describe('FilterPanel seller', () => {
  it('applies the trimmed seller name on submit', () => {
    const onChange = panel({})

    fireEvent.change(screen.getByLabelText('Seller name'), { target: { value: '  Acme  ' } })
    fireEvent.submit(screen.getByRole('search', { name: 'Filter by seller' }))

    expect(onChange).toHaveBeenCalledWith({ seller: 'Acme' })
  })

  it('clears the filter when submitted empty', () => {
    const onChange = panel({ seller: 'Acme' })
    expect(screen.getByLabelText('Seller name')).toHaveValue('Acme')

    fireEvent.change(screen.getByLabelText('Seller name'), { target: { value: '   ' } })
    fireEvent.submit(screen.getByRole('search', { name: 'Filter by seller' }))

    expect(onChange).toHaveBeenCalledWith({ seller: undefined })
  })

  it('caps the input at the server limit', () => {
    panel({})
    expect(screen.getByLabelText('Seller name')).toHaveAttribute('maxlength', '200')
  })
})

describe('ActiveFilters chips', () => {
  const chips = (query: ProductQuery) => {
    const onChange = vi.fn()
    render(<ActiveFilters query={query} categories={[]} onChange={onChange} onClearAll={vi.fn()} />)
    return onChange
  }

  it('lists the seller and every switch that is on', () => {
    chips({ seller: 'Acme', inStock: true, verifiedSeller: true })

    expect(screen.getByRole('button', { name: 'Remove filter Seller: Acme' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove filter In stock only' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove filter Verified sellers' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /On sale/ })).not.toBeInTheDocument()
  })

  it('removes just the clicked filter', () => {
    const onChange = chips({ featured: true, onSale: true })

    fireEvent.click(screen.getByRole('button', { name: 'Remove filter Top picks' }))

    expect(onChange).toHaveBeenCalledWith({ featured: undefined })
  })
})
