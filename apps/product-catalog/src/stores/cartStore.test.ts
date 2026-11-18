/**
 * Cart store (TD-TEST-02): anonymous local cart, sign-in merge, optimistic edits with rollback,
 * and that a signed-in cart is never left in localStorage. cartApi is mocked.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CartResponse } from '../types'

const api = vi.hoisted(() => ({
  addItem: vi.fn(),
  removeItem: vi.fn(),
  setQuantity: vi.fn(),
  clearCart: vi.fn(),
  mergeCart: vi.fn(),
  getCart: vi.fn(),
  saveForLater: vi.fn(),
  moveSavedToCart: vi.fn(),
  removeSaved: vi.fn(),
}))
vi.mock('../services/cartApi', () => ({ cartApi: api }))

import { useCartStore } from './cartStore'

const shirt = { name: 'Shirt', priceAmount: 499, priceCurrency: 'INR' }

function serverCart(lines: { productId: string; quantity: number }[]): CartResponse {
  return {
    items: lines.map((l) => ({
      productId: l.productId,
      quantity: l.quantity,
      name: 'Server ' + l.productId,
      unitPrice: 499,
      currency: 'INR',
      imageUrl: null,
      lineTotal: 499 * l.quantity,
      addedAt: '2026-11-17T00:00:00Z',
      variantId: null,
      variantLabel: null,
      offerId: null,
    })),
    currency: 'INR',
    itemCount: lines.length,
    subtotal: 0,
    savedItems: [],
  } as unknown as CartResponse
}

const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  useCartStore.setState({ items: [], saved: [], serverBacked: false })
})

describe('anonymous cart', () => {
  it('adds to an existing line and caps the quantity at 99', () => {
    const cart = useCartStore.getState()
    cart.addItem('p1', 60, shirt)
    cart.addItem('p1', 60, shirt)

    expect(useCartStore.getState().items).toHaveLength(1)
    expect(useCartStore.getState().items[0]?.quantity).toBe(99)
    expect(api.addItem).not.toHaveBeenCalled()
  })

  it('setting a quantity of 0 removes the line', () => {
    useCartStore.getState().addItem('p1', 2, shirt)
    useCartStore.getState().updateQuantity('p1', 0)

    expect(useCartStore.getState().items).toEqual([])
  })

  it('is persisted locally while anonymous', () => {
    useCartStore.getState().addItem('p1', 1, shirt)

    expect(localStorage.getItem('cart-store')).toContain('p1')
  })
})

describe('signing in', () => {
  it('merges the anonymous lines into the account, then adopts the server cart', async () => {
    useCartStore.getState().addItem('p1', 2, shirt)
    api.mergeCart.mockResolvedValue(serverCart([{ productId: 'p1', quantity: 3 }, { productId: 'p2', quantity: 1 }]))

    await useCartStore.getState().attachServer()

    expect(api.mergeCart).toHaveBeenCalledWith([
      expect.objectContaining({ productId: 'p1', quantity: 2, name: 'Shirt', unitPrice: 499, currency: 'INR' }),
    ])
    const state = useCartStore.getState()
    expect(state.serverBacked).toBe(true)
    expect(state.items.map((i) => [i.productId, i.quantity])).toEqual([
      ['p1', 3],
      ['p2', 1],
    ])
  })

  it('keeps the anonymous cart and stays local if the merge fails', async () => {
    useCartStore.getState().addItem('p1', 2, shirt)
    api.mergeCart.mockRejectedValue(new Error('cart-service down'))

    await useCartStore.getState().attachServer()

    const state = useCartStore.getState()
    expect(state.serverBacked).toBe(false)
    expect(state.items.map((i) => i.productId)).toEqual(['p1'])
  })

  it('never leaves a signed-in cart in localStorage', async () => {
    api.getCart.mockResolvedValue(serverCart([{ productId: 'p9', quantity: 1 }]))

    await useCartStore.getState().attachServer()

    expect(localStorage.getItem('cart-store') ?? '').not.toContain('p9')
  })

  it('signing out empties the cart and returns to local mode', async () => {
    api.getCart.mockResolvedValue(serverCart([{ productId: 'p9', quantity: 1 }]))
    await useCartStore.getState().attachServer()

    useCartStore.getState().detachServer()

    expect(useCartStore.getState()).toMatchObject({ items: [], serverBacked: false })
  })
})

describe('signed-in edits', () => {
  beforeEach(() => {
    useCartStore.setState({ items: [], saved: [], serverBacked: true })
  })

  it('applies an add at once, then adopts the server answer', async () => {
    api.addItem.mockResolvedValue(serverCart([{ productId: 'p1', quantity: 1 }]))

    useCartStore.getState().addItem('p1', 1, shirt)
    expect(useCartStore.getState().items).toHaveLength(1)
    await flush()

    expect(useCartStore.getState().items[0]?.snapshot?.name).toBe('Server p1')
  })

  it('rolls an add back when the server refuses it', async () => {
    api.addItem.mockRejectedValue(new Error('currency mismatch'))

    useCartStore.getState().addItem('p1', 1, shirt)
    await flush()

    expect(useCartStore.getState().items).toEqual([])
  })
})
