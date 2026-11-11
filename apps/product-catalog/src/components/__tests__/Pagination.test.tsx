import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { Pagination, getPageRange } from '../Pagination'

// 1-based labels make the expectations read like the UI.
const labels = (current: number, total: number) =>
  getPageRange(current, total).map((item) => (item === 'ellipsis' ? '…' : String(item + 1)))

describe('getPageRange', () => {
  it('lists every page when they all fit', () => {
    expect(labels(0, 1)).toEqual(['1'])
    expect(labels(3, 7)).toEqual(['1', '2', '3', '4', '5', '6', '7'])
  })

  it('keeps first, last and the current page with one sibling each side', () => {
    expect(labels(4, 21)).toEqual(['1', '…', '4', '5', '6', '…', '21'])
  })

  it('keeps a constant width at both edges', () => {
    expect(labels(0, 21)).toEqual(['1', '2', '3', '4', '5', '…', '21'])
    expect(labels(20, 21)).toEqual(['1', '…', '17', '18', '19', '20', '21'])
  })

  it('never hides a single page behind an ellipsis', () => {
    expect(labels(3, 21)).toEqual(['1', '2', '3', '4', '5', '…', '21'])
    expect(labels(17, 21)).toEqual(['1', '…', '17', '18', '19', '20', '21'])
  })

  it('returns nothing for an empty result', () => {
    expect(getPageRange(0, 0)).toEqual([])
  })
})

describe('Pagination', () => {
  const onPageChange = vi.fn()
  const renderAt = (page: number, totalPages = 6, totalElements = 105) =>
    render(
      <Pagination
        page={page}
        pageSize={20}
        totalPages={totalPages}
        totalElements={totalElements}
        onPageChange={onPageChange}
      />,
    )

  beforeEach(() => {
    onPageChange.mockReset()
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  })

  it('shows the item range below the controls', () => {
    renderAt(4)

    expect(screen.getByText(/Showing/).textContent).toBe('Showing 81–100 of 105 products')
  })

  it('caps the range at the total on the last page', () => {
    renderAt(5)

    expect(screen.getByText(/Showing/).textContent).toBe('Showing 101–105 of 105 products')
  })

  it('marks the current page and reports a clicked page', () => {
    renderAt(0)
    expect(screen.getByRole('button', { name: 'Page 1' })).toHaveAttribute('aria-current', 'page')

    fireEvent.click(screen.getByRole('button', { name: 'Page 3' }))

    expect(onPageChange).toHaveBeenCalledWith(2)
    expect(window.scrollTo).toHaveBeenCalled()
  })

  it('does not report a click on the current page', () => {
    renderAt(2)

    fireEvent.click(screen.getByRole('button', { name: 'Page 3' }))

    expect(onPageChange).not.toHaveBeenCalled()
  })

  it('disables Previous on the first page and Next on the last', () => {
    const { unmount } = renderAt(0)
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
    unmount()

    renderAt(5)
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
  })

  it('clamps a stale page that is beyond the last page', () => {
    renderAt(9)

    expect(screen.getByRole('button', { name: 'Page 6' })).toHaveAttribute('aria-current', 'page')
  })

  it('hides the controls when everything fits on one page', () => {
    renderAt(0, 1, 5)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText(/Showing/).textContent).toBe('Showing 1–5 of 5 products')
  })
})
