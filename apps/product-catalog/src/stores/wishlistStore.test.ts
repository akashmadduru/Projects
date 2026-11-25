/**
 * Wishlist store, anonymous behaviour (TD-TEST-02): local add/remove/toggle/clear with no API call,
 * duplicate and variant handling, and persistence to localStorage. cartApi is mocked.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getWishlist: vi.fn(),
  saveWishlistItem: vi.fn(),
  removeWishlistItem: vi.fn(),
  mergeWishlist: vi.fn(),
}))
vi.mock('../services/cartApi', () => ({ cartApi: api }))

import { useWishlistStore } from './wishlistStore'

const snap = { name: 'Shirt', price: 499, currency: 'INR' }

function persisted(): { state: { items: unknown[] }; version: number } | null {
  const raw = localStorage.getItem('wishlist-store')
  return raw ? JSON.parse(raw) : null
}

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  useWishlistStore.setState({ items: [], serverBacked: false })
})

afterEach(() => {
  localStorage.clear()
  useWishlistStore.setState({ items: [], serverBacked: false })
})

function expectNoApiCall() {
  for (const fn of Object.values(api)) expect(fn).not.toHaveBeenCalled()
}

describe('anonymous wishlist', () => {
  it('adds an item locally with a timestamp and no API call', () => {
    useWishlistStore.getState().addItem('p1', snap)
    const [item] = useWishlistStore.getState().items
    expect(item).toMatchObject({ productId: 'p1', snapshot: snap })
    expect(item?.variantId).toBeUndefined()
    expect(typeof item?.addedAt).toBe('number')
    expectNoApiCall()
  })

  it('adds an item without a snapshot when anonymous', () => {
    useWishlistStore.getState().addItem('p1')
    expect(useWishlistStore.getState().items).toHaveLength(1)
    expectNoApiCall()
  })

  it('ignores a duplicate add of the same productId and variantId', () => {
    const s = useWishlistStore.getState()
    s.addItem('p1', snap, 'v1')
    s.addItem('p1', snap, 'v1')
    s.addItem('p2', snap)
    s.addItem('p2', snap)
    expect(useWishlistStore.getState().items).toHaveLength(2)
    expectNoApiCall()
  })

  it('keeps the same product with a different variantId as a separate entry', () => {
    const s = useWishlistStore.getState()
    s.addItem('p1', snap, 'v1')
    s.addItem('p1', snap, 'v2')
    s.addItem('p1', snap)
    expect(useWishlistStore.getState().items.map((i) => i.variantId)).toEqual(['v1', 'v2', undefined])
  })

  it('removes only the matching product and variant', () => {
    const s = useWishlistStore.getState()
    s.addItem('p1', snap, 'v1')
    s.addItem('p1', snap, 'v2')
    s.addItem('p2', snap)
    s.removeItem('p1', 'v1')
    expect(useWishlistStore.getState().items.map((i) => [i.productId, i.variantId])).toEqual([
      ['p1', 'v2'],
      ['p2', undefined],
    ])
    s.removeItem('p2')
    expect(useWishlistStore.getState().items).toHaveLength(1)
    s.removeItem('missing')
    expect(useWishlistStore.getState().items).toHaveLength(1)
    expectNoApiCall()
  })

  it('toggles an item in and out', () => {
    const s = useWishlistStore.getState()
    s.toggleItem('p1', snap, 'v1')
    expect(useWishlistStore.getState().isInWishlist('p1', 'v1')).toBe(true)
    s.toggleItem('p1', snap, 'v1')
    expect(useWishlistStore.getState().isInWishlist('p1', 'v1')).toBe(false)
    expect(useWishlistStore.getState().items).toEqual([])
    expectNoApiCall()
  })

  it('isInWishlist distinguishes variants', () => {
    useWishlistStore.getState().addItem('p1', snap, 'v1')
    const s = useWishlistStore.getState()
    expect(s.isInWishlist('p1', 'v1')).toBe(true)
    expect(s.isInWishlist('p1', 'v2')).toBe(false)
    expect(s.isInWishlist('p1')).toBe(false)
    expect(s.isInWishlist('p2')).toBe(false)
  })

  it('getItemCount counts entries and clearWishlist empties locally', () => {
    const s = useWishlistStore.getState()
    expect(s.getItemCount()).toBe(0)
    s.addItem('p1', snap)
    s.addItem('p1', snap, 'v1')
    s.addItem('p2', snap)
    expect(useWishlistStore.getState().getItemCount()).toBe(3)
    useWishlistStore.getState().clearWishlist()
    expect(useWishlistStore.getState().getItemCount()).toBe(0)
    expectNoApiCall()
  })
})

describe('anonymous persistence', () => {
  it('writes the anonymous list to localStorage under wishlist-store', () => {
    useWishlistStore.getState().addItem('p1', snap, 'v1')
    const saved = persisted()
    expect(saved?.version).toBe(1)
    expect(saved?.state.items).toEqual([
      expect.objectContaining({ productId: 'p1', variantId: 'v1', snapshot: snap }),
    ])
    expect(saved?.state).not.toHaveProperty('serverBacked')
  })

  it('updates the persisted list on remove and clear', () => {
    const s = useWishlistStore.getState()
    s.addItem('p1', snap)
    s.addItem('p2', snap)
    s.removeItem('p1')
    expect(persisted()?.state.items).toHaveLength(1)
    useWishlistStore.getState().clearWishlist()
    expect(persisted()?.state.items).toEqual([])
  })
})
