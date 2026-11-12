import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AddToCartButton } from '../AddToCartButton'
import { CategoryTiles } from '../CategoryTiles'
import { Drawer } from '../Drawer'
import { singleProduct } from '../../test/fixtures'
import { useCartStore, useUiStore } from '../../stores'
import type { CategoryNode } from '../../hooks'

describe('AddToCartButton feedback', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    useCartStore.setState({ items: [] })
    useUiStore.setState({ toasts: [] })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('confirms with "Added" and then returns to its resting label', () => {
    render(<AddToCartButton product={singleProduct} />)

    fireEvent.click(screen.getByRole('button', { name: 'Add to cart' }))
    expect(screen.getByRole('button', { name: 'Added' })).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(1400))
    expect(screen.getByRole('button', { name: 'Add to cart' })).toBeInTheDocument()
  })

  it('keeps adding while the confirmation is showing', () => {
    render(<AddToCartButton product={singleProduct} />)

    fireEvent.click(screen.getByRole('button', { name: 'Add to cart' }))
    fireEvent.click(screen.getByRole('button', { name: 'Added' }))

    expect(useCartStore.getState().items[0]?.quantity).toBe(2)
  })
})

describe('Drawer exit transition', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('stays mounted while it animates out, then unmounts', () => {
    const { rerender } = render(
      <Drawer open onClose={() => {}} title="Your cart">
        <p>contents</p>
      </Drawer>,
    )
    expect(screen.getByRole('dialog', { name: 'Your cart' })).toBeInTheDocument()

    rerender(
      <Drawer open={false} onClose={() => {}} title="Your cart">
        <p>contents</p>
      </Drawer>,
    )
    expect(screen.getByText('contents')).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(350))
    expect(screen.queryByText('contents')).not.toBeInTheDocument()
  })

  it('closes on Escape', () => {
    const onClose = vi.fn()
    render(
      <Drawer open onClose={onClose} title="Filters">
        <p>contents</p>
      </Drawer>,
    )

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(onClose).toHaveBeenCalled()
  })
})

describe('CategoryTiles', () => {
  const node = (id: string, name: string, children = 0): CategoryNode => ({
    id,
    name,
    description: null,
    parentCategoryId: null,
    depthLevel: 0,
    active: true,
    version: 0,
    children: Array.from({ length: children }, (_, i) => ({
      id: `${id}-${i}`,
      name: `${name} ${i}`,
      description: null,
      parentCategoryId: id,
      depthLevel: 1,
      active: true,
      version: 0,
    })),
  })

  it('links each category to its filtered catalog', () => {
    render(
      <MemoryRouter>
        <CategoryTiles categories={[node('c1', 'Books', 2), node('c2', 'Sports')]} />
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: /Books/ })).toHaveAttribute('href', '/?category=c1')
    expect(screen.getByText('2 collections')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Sports/ })).toHaveAttribute('href', '/?category=c2')
  })

  it('renders nothing without categories', () => {
    const { container } = render(
      <MemoryRouter>
        <CategoryTiles categories={[]} />
      </MemoryRouter>,
    )

    expect(container).toBeEmptyDOMElement()
  })
})
