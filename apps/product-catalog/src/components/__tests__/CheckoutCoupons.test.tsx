/**
 * CheckoutCoupons: code box + best coupon + a few quick picks, everything else in a dialog.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, cleanup, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CheckoutCoupons } from '../CheckoutCoupons'
import { render } from '../../test/test-utils'
import type { AvailableCoupon, CouponValidation } from '../../types'

const coupon = (code: string, discountAmount: number | null, extra: Partial<AvailableCoupon> = {}): AvailableCoupon => ({
  code,
  description: `${code} description`,
  discountAmount,
  minOrderAmount: null,
  ...extra,
})

const MANY: AvailableCoupon[] = [
  coupon('C05', 5),
  coupon('C20', 20, { minOrderAmount: 100 }),
  coupon('C10', 10),
  coupon('C01', 1),
  coupon('C15', 15),
  coupon('C02', 2),
  coupon('C03', 3),
]

function setup(over: Partial<React.ComponentProps<typeof CheckoutCoupons>> = {}) {
  const onApply = vi.fn().mockResolvedValue(true)
  const onRemove = vi.fn()
  const utils = render(
    <CheckoutCoupons applied={null} available={MANY} currency="USD" busy={false} error={undefined} onApply={onApply} onRemove={onRemove} {...over} />,
  )
  return { onApply, onRemove, ...utils }
}

describe('CheckoutCoupons', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  afterEach(() => cleanup())

  it('shows only the best three coupons inline, best first, and a "See all" button for the rest', () => {
    setup()
    const list = screen.getByRole('list', { name: 'Suggested coupons' })
    const rows = within(list).getAllByRole('listitem')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('C20')
    expect(rows[0]).toHaveTextContent('Best value')
    expect(rows[1]).toHaveTextContent('C15')
    expect(rows[2]).toHaveTextContent('C10')
    expect(screen.queryByText('C01')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'See all 7 coupons' })).toBeInTheDocument()
  })

  it('has no "See all" button when everything fits inline, and marks no winner for a single coupon', () => {
    setup({ available: [coupon('ONLY', 5)] })
    expect(screen.queryByRole('button', { name: /See all/ })).not.toBeInTheDocument()
    expect(screen.queryByText('Best value')).not.toBeInTheDocument()
  })

  it('shows minimum-order and saving details on a card', () => {
    setup()
    const best = screen.getByRole('list', { name: 'Suggested coupons' }).querySelector('li')!
    expect(best).toHaveTextContent('min $100.00')
    expect(best).toHaveTextContent('You save $20.00')
  })

  it('applies a quick pick with one click', async () => {
    const user = userEvent.setup()
    const { onApply } = setup()
    await user.click(screen.getByRole('button', { name: 'Apply coupon C15' }))
    expect(onApply).toHaveBeenCalledWith('C15')
  })

  it('applies a typed code on Enter and on the Apply button', async () => {
    const user = userEvent.setup()
    const { onApply } = setup({ available: [] })
    const input = screen.getByLabelText('Have a coupon code?')
    await user.type(input, 'save10{Enter}')
    expect(onApply).toHaveBeenCalledWith('SAVE10')
    await user.type(input, 'again')
    await user.click(screen.getByRole('button', { name: 'Apply' }))
    expect(onApply).toHaveBeenLastCalledWith('AGAIN')
  })

  it('opens a dialog with every coupon, sorted by saving, and applies from it then closes', async () => {
    const user = userEvent.setup()
    const { onApply } = setup()
    await user.click(screen.getByRole('button', { name: 'See all 7 coupons' }))
    const dialog = screen.getByRole('dialog', { name: 'Coupons for this order' })
    const rows = within(within(dialog).getByRole('list', { name: 'All available coupons' })).getAllByRole('listitem')
    expect(rows.map((r) => r.querySelector('.font-mono')?.textContent)).toEqual(['C20', 'C15', 'C10', 'C05', 'C03', 'C02', 'C01'])
    await user.click(within(dialog).getByRole('button', { name: 'Apply coupon C01' }))
    expect(onApply).toHaveBeenCalledWith('C01')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('keeps the dialog open and shows the error when applying from it fails', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn().mockResolvedValue(false)
    setup({ onApply, error: 'Coupon expired' })
    await user.click(screen.getByRole('button', { name: 'See all 7 coupons' }))
    await user.click(screen.getByRole('button', { name: 'Apply coupon C20' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Coupon expired')
  })

  it('search keeps focus while typing and filters the list', async () => {
    const user = userEvent.setup()
    setup()
    await user.click(screen.getByRole('button', { name: 'See all 7 coupons' }))
    const search = screen.getByRole('searchbox', { name: 'Search coupons' })
    await user.type(search, 'c1')
    expect(search).toHaveFocus() // would fail if the dialog remounted on each keystroke
    const rows = within(screen.getByRole('list', { name: 'All available coupons' })).getAllByRole('listitem')
    expect(rows.map((r) => r.querySelector('.font-mono')?.textContent)).toEqual(['C15', 'C10', 'C01'])
    await user.clear(search)
    await user.type(search, 'zzz')
    expect(screen.getByText(/No coupons match/)).toBeInTheDocument()
  })

  it('collapses to a confirmation once applied, with Change and Remove', async () => {
    const user = userEvent.setup()
    const applied: CouponValidation = { code: 'C20', description: null, discountAmount: 20, currency: 'USD' }
    const { onRemove } = setup({ applied })
    expect(screen.getByText('C20')).toBeInTheDocument()
    expect(screen.getByText(/you save \$20\.00/)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Suggested coupons' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await user.click(screen.getByRole('button', { name: 'Remove' }))
    expect(onRemove).toHaveBeenCalled()
  })
})
