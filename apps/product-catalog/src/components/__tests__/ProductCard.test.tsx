/**
 * ProductCard: what a shopper sees and can do from a listing card
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ProductCard } from '../ProductCard'
import { singleProduct } from '../../test/fixtures'
import { useCartStore, useUiStore, useWishlistStore } from '../../stores'
import type { Product } from '../../types'

const deal: Product = {
  ...singleProduct,
  priceAmount: 16.99,
  listPrice: 18.69,
  averageRating: 4.5,
  ratingCount: 1250,
  featured: true,
  sellerName: 'BookStore',
  sellerVerified: true,
}

function renderCard(product: Product = deal) {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<ProductCard product={product} />} />
        <Route path="/products/:id" element={<p>detail page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProductCard', () => {
  beforeEach(() => {
    useCartStore.setState({ items: [] })
    useWishlistStore.setState({ items: [] })
    useUiStore.setState({ toasts: [] })
  })

  it('links the product name to its detail page', () => {
    renderCard()

    fireEvent.click(screen.getByRole('link', { name: deal.name }))

    expect(screen.getByText('detail page')).toBeInTheDocument()
  })

  it('shows the deal price, the struck list price and the saving', () => {
    renderCard()

    expect(screen.getByText('$16.99')).toBeInTheDocument()
    expect(screen.getByText('$18.69')).toHaveClass('line-through')
    expect(screen.getByText('9% off')).toBeInTheDocument()
    expect(screen.getByText('-9%')).toBeInTheDocument()
  })

  it('omits the discount when there is no list price above the selling price', () => {
    renderCard({ ...deal, listPrice: null })

    expect(screen.queryByText(/% off/)).not.toBeInTheDocument()
  })

  it('shows the rating chip with a compact count', () => {
    renderCard()

    expect(screen.getByLabelText('Rated 4.5 out of 5')).toBeInTheDocument()
    expect(screen.getByText('(1.3K)')).toBeInTheDocument()
  })

  it('says so when a product has no ratings', () => {
    renderCard({ ...deal, averageRating: null, ratingCount: 0 })

    expect(screen.getByText('No ratings yet')).toBeInTheDocument()
  })

  it('badges featured products and verified sellers', () => {
    renderCard()

    expect(screen.getByText('Top pick')).toBeInTheDocument()
    expect(screen.getByText('by BookStore')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Verified seller' })).toBeInTheDocument()
  })

  it('warns when stock is low', () => {
    renderCard({ ...deal, lastKnownQuantity: 3 })

    expect(screen.getByText('Only 3 left')).toBeInTheDocument()
  })

  it('adds to the cart with a snapshot, confirms, and stays on the listing', () => {
    renderCard()

    fireEvent.click(screen.getByRole('button', { name: 'Add to cart' }))

    const [item] = useCartStore.getState().items
    expect(item).toMatchObject({ productId: deal.id, quantity: 1 })
    expect(item?.snapshot).toMatchObject({ name: deal.name, priceAmount: 16.99, priceCurrency: 'USD' })
    expect(useUiStore.getState().toasts[0]?.message).toBe(`Added ${deal.name} to your cart`)
    expect(screen.queryByText('detail page')).not.toBeInTheDocument()
  })

  it('cannot be added to the cart when out of stock', () => {
    renderCard({ ...deal, lastKnownQuantity: 0 })

    expect(screen.getByRole('button', { name: 'Unavailable' })).toBeDisabled()
    expect(screen.getByText('Out of stock')).toBeInTheDocument()
  })

  it('toggles the wishlist without navigating', () => {
    renderCard()
    const heart = screen.getByRole('button', { name: `Save ${deal.name} to wishlist` })
    expect(heart).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(heart)

    expect(useWishlistStore.getState().isInWishlist(deal.id)).toBe(true)
    expect(screen.getByRole('button', { name: `Remove ${deal.name} from wishlist` })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.queryByText('detail page')).not.toBeInTheDocument()
  })
})
