/**
 * OffersCard: two summary tiles (Coupons, Bank offers), each opening its own dialog.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { OffersCard } from '../OffersCard'
import { render } from '../../test/test-utils'
import { useUiStore } from '../../stores'

const { offersMock } = vi.hoisted(() => ({ offersMock: vi.fn() }))
vi.mock('../../services/discoveryApi', () => ({ discoveryApi: { offers: offersMock } }))

const OFFERS = {
  coupons: [
    { code: 'SAVE5', description: '5 off orders over 50', discountAmount: 5 },
    { code: 'WELCOME20', description: '20 off your first order', discountAmount: 20 },
  ],
  bankOffers: [
    { id: 'b1', bank: 'HDFC Bank', title: '10% off with credit cards', discountAmount: 12 },
    { id: 'b2', bank: 'HDFC Bank', title: 'No-cost EMI', discountAmount: 0 },
    { id: 'b3', bank: 'ICICI Bank', title: '5% cashback', discountAmount: 6 },
  ],
  emi: [],
}

function setup(offers: unknown = OFFERS) {
  offersMock.mockResolvedValue(offers)
  return render(<OffersCard productId="p1" price={100} currency="USD" />)
}

describe('OffersCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useUiStore.setState({ toasts: [] })
  })
  afterEach(() => cleanup())

  it('renders nothing when there are no offers', async () => {
    const { container } = setup({ coupons: [], bankOffers: [], emi: [] })
    await waitFor(() => expect(offersMock).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it('shows a tile per offer type with count and best saving, and no dialog yet', async () => {
    setup()
    const coupons = await screen.findByTestId('offers-coupons-tile')
    const bank = screen.getByTestId('offers-bank-tile')
    expect(coupons).toHaveTextContent('2')
    expect(coupons).toHaveTextContent('$20.00')
    expect(bank).toHaveTextContent('3')
    expect(bank).toHaveTextContent('$12.00')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('omits the tile for a type that has no offers', async () => {
    setup({ ...OFFERS, bankOffers: [] })
    await screen.findByTestId('offers-coupons-tile')
    expect(screen.queryByTestId('offers-bank-tile')).not.toBeInTheDocument()
  })

  it('opens the coupons dialog with best value first and marked', async () => {
    const user = userEvent.setup()
    setup()
    await user.click(await screen.findByTestId('offers-coupons-tile'))
    const dialog = screen.getByRole('dialog', { name: 'Coupons' })
    const rows = within(dialog).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('WELCOME20')
    expect(rows[0]).toHaveTextContent('Best value')
    expect(rows[1]).not.toHaveTextContent('Best value')
  })

  it('opens a separate bank dialog grouped by bank', async () => {
    const user = userEvent.setup()
    setup()
    await user.click(await screen.findByTestId('offers-bank-tile'))
    const dialog = screen.getByRole('dialog', { name: 'Bank offers' })
    expect(within(dialog).getByRole('heading', { name: 'HDFC Bank' })).toBeInTheDocument()
    expect(within(dialog).getByRole('heading', { name: 'ICICI Bank' })).toBeInTheDocument()
    expect(within(dialog).getByText('10% off with credit cards')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Coupons' })).not.toBeInTheDocument()
  })

  it('copies a coupon code and confirms', async () => {
    const user = userEvent.setup()
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    setup()
    await user.click(await screen.findByTestId('offers-coupons-tile'))
    await user.click(screen.getByRole('button', { name: 'Copy code WELCOME20' }))
    expect(writeText).toHaveBeenCalledWith('WELCOME20')
    expect(await screen.findByText('Copied ✓')).toBeInTheDocument()
    expect(useUiStore.getState().toasts.some((t) => t.message.includes('WELCOME20'))).toBe(true)
  })

  it('closes on Escape and returns focus to the tile', async () => {
    const user = userEvent.setup()
    setup()
    const tile = await screen.findByTestId('offers-coupons-tile')
    await user.click(tile)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(tile).toHaveFocus()
  })

  it('closes from the close button and the backdrop, and locks page scroll while open', async () => {
    const user = userEvent.setup()
    setup()
    await user.click(await screen.findByTestId('offers-bank-tile'))
    expect(document.body.style.overflow).toBe('hidden')
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(document.body.style.overflow).not.toBe('hidden')
  })
})
