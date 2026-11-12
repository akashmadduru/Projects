import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { OverviewPage } from '../OverviewPage'
import { orderApi } from '../../../services/orderApi'
import { userApi } from '../../../services/userApi'
import { useAuthStore, useCartStore, useUiStore, useWishlistStore } from '../../../stores'
import type { Address, AuthUser, OrderSummary, UserProfile } from '../../../types'

vi.mock('../../../services/userApi', () => ({
  userApi: { getProfile: vi.fn(), listAddresses: vi.fn() },
}))
vi.mock('../../../services/orderApi', () => ({
  orderApi: { list: vi.fn() },
}))

const user: AuthUser = {
  id: 'u1',
  email: 'ada@example.com',
  emailVerified: true,
  displayName: 'Ada Lovelace',
  status: 'ACTIVE',
  roles: ['VIEWER'],
  createdAt: '2026-01-05T00:00:00Z',
}

const profile: UserProfile = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  phone: '+1 555 123 4567',
  createdAt: '2026-01-05T00:00:00Z',
}

const address = (over: Partial<Address> = {}): Address => ({
  id: 'a1',
  label: 'Home',
  fullName: 'Ada Lovelace',
  phone: null,
  line1: '1 Analytical Way',
  line2: null,
  city: 'London',
  state: null,
  postalCode: 'N1 1AA',
  country: 'GB',
  isDefault: true,
  createdAt: '2026-01-06T00:00:00Z',
  updatedAt: '2026-01-06T00:00:00Z',
  ...over,
})

const order = (id: string, status: OrderSummary['status'], total: number): OrderSummary => ({
  id,
  status,
  currency: 'USD',
  totalAmount: total,
  placedAt: '2026-02-01T10:00:00Z',
  itemCount: 2,
})

const page = (content: OrderSummary[], totalElements = content.length) => ({
  content,
  page: { size: 50, number: 0, totalElements, totalPages: 1 },
})

function renderOverview() {
  return render(
    <MemoryRouter>
      <OverviewPage />
    </MemoryRouter>,
  )
}

/** The stat tile whose label matches, so assertions read like the screen. */
const tile = (label: RegExp | string) => screen.getByRole('link', { name: new RegExp(typeof label === 'string' ? label : label.source) })

describe('OverviewPage', () => {
  beforeEach(() => {
    vi.mocked(userApi.getProfile).mockResolvedValue(profile)
    vi.mocked(userApi.listAddresses).mockResolvedValue([address()])
    vi.mocked(orderApi.list).mockResolvedValue(page([order('11111111-aaaa', 'PLACED', 40), order('22222222-bbbb', 'CANCELLED', 99)]))
    useAuthStore.setState({ status: 'authenticated', user })
    useCartStore.setState({ items: [], serverBacked: false })
    useWishlistStore.setState({ items: [], serverBacked: false })
    useUiStore.setState({ cartOpen: false, toasts: [] })
  })

  it('greets the person by first name', async () => {
    renderOverview()

    expect(await screen.findByRole('heading', { level: 2, name: /Ada/ })).toBeInTheDocument()
  })

  it('summarises orders, spend and addresses once loaded', async () => {
    renderOverview()

    await waitFor(() => expect(tile('Orders')).toHaveTextContent('1 placed · 1 cancelled'))
    expect(tile('Orders')).toHaveTextContent(/2\s*Orders/)
    expect(tile('Lifetime spend')).toHaveTextContent('$40.00')
    expect(tile('Lifetime spend')).toHaveTextContent('Excludes cancelled orders')
    expect(tile('Saved addresses')).toHaveTextContent('Default: Home')
  })

  it('labels spend honestly when only the latest orders were counted', async () => {
    vi.mocked(orderApi.list).mockResolvedValue(page([order('11111111-aaaa', 'PLACED', 10)], 80))
    renderOverview()

    expect(await screen.findByRole('link', { name: /Spent \(latest 50\)/ })).toBeInTheDocument()
  })

  it('lists recent orders newest first with status and total', async () => {
    renderOverview()

    const card = (await screen.findByRole('heading', { name: 'Recent orders' })).closest('section')!
    const rows = within(card).getAllByRole('link', { name: /#/ })
    expect(rows[0]).toHaveAttribute('href', '/orders/11111111-aaaa')
    expect(rows[0]).toHaveTextContent('Placed')
    expect(rows[0]).toHaveTextContent('$40.00')
    expect(rows[1]).toHaveTextContent('Cancelled')
  })

  it('shows empty states for a brand-new account', async () => {
    vi.mocked(orderApi.list).mockResolvedValue(page([]))
    vi.mocked(userApi.listAddresses).mockResolvedValue([])
    renderOverview()

    expect(await screen.findByText('When you place an order it will show up here.')).toBeInTheDocument()
    expect(screen.getByText(/No saved address yet/)).toBeInTheDocument()
    const wishlistCard = screen.getByRole('heading', { name: 'From your wishlist' }).closest('section')!
    expect(within(wishlistCard).getByText('Nothing saved yet')).toBeInTheDocument()
    expect(tile('Lifetime spend')).toHaveTextContent('Nothing spent yet')
  })

  it('keeps the other cards working when orders fail, and can retry', async () => {
    vi.mocked(orderApi.list).mockRejectedValueOnce(new Error('boom')).mockResolvedValue(page([order('33333333-cccc', 'PLACED', 5)]))
    renderOverview()

    const retry = await screen.findByRole('button', { name: 'Try again' })
    expect(screen.getByText('Delivery address')).toBeInTheDocument()
    expect(tile('Saved addresses')).toHaveTextContent('Default: Home')

    fireEvent.click(retry)

    expect(await screen.findByText('#33333333')).toBeInTheDocument()
  })

  it('reports profile strength and sends incomplete steps to where they are fixed', async () => {
    vi.mocked(userApi.getProfile).mockResolvedValue({ ...profile, phone: null })
    vi.mocked(userApi.listAddresses).mockResolvedValue([])
    renderOverview()

    const phoneStep = await screen.findByRole('link', { name: /Add a phone number/ })
    expect(phoneStep).toHaveAttribute('href', '/account/profile')
    expect(screen.getByRole('link', { name: /Save a delivery address/ })).toHaveAttribute('href', '/account/addresses')
    expect(screen.getByRole('img', { name: 'Profile 50% complete' })).toBeInTheDocument()
  })

  it('celebrates a complete profile', async () => {
    renderOverview()

    expect(await screen.findByText('Your profile is complete. Nice work!')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Profile 100% complete' })).toBeInTheDocument()
  })

  it('shows the wishlist and cart from the local stores', async () => {
    useWishlistStore.setState({
      items: [{ productId: 'p1', addedAt: 1, snapshot: { name: 'Lamp', price: 20, currency: 'USD' } }],
    })
    useCartStore.setState({
      items: [{ productId: 'p2', quantity: 3, addedAt: 1, snapshot: { name: 'Mug', priceAmount: 5, priceCurrency: 'USD' } }],
    })
    renderOverview()

    await screen.findByRole('heading', { name: 'From your wishlist' })
    expect(tile('Wishlist')).toHaveTextContent('Saved for later')
    expect(screen.getByTitle('Lamp')).toHaveAttribute('href', '/products/p1')
    expect(screen.getByRole('button', { name: /In your cart/ })).toHaveTextContent('Subtotal $15.00')
  })

  it('opens the cart drawer from the cart tile', async () => {
    renderOverview()
    await screen.findByRole('heading', { name: 'Recent orders' })

    fireEvent.click(screen.getByRole('button', { name: /In your cart/ }))

    expect(useUiStore.getState().cartOpen).toBe(true)
  })
})
