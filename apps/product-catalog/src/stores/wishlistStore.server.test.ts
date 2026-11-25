/**
 * Wishlist store, signed-in behaviour (TD-TEST-02): optimistic server mutations, reconcile and
 * rollback, merge on sign-in in chunks of 200, detach, and what is (not) persisted. cartApi is mocked.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WishlistItemResponse } from '../types'
import { ApiErrorImpl } from '../lib/problem'

const api = vi.hoisted(() => ({
  getWishlist: vi.fn(),
  saveWishlistItem: vi.fn(),
  removeWishlistItem: vi.fn(),
  mergeWishlist: vi.fn(),
}))
vi.mock('../services/cartApi', () => ({ cartApi: api }))

import { useWishlistStore } from './wishlistStore'
import { useUiStore } from './uiStore'

const snap = { name: 'Shirt', price: 499, currency: 'INR', imageUrl: 'https://img/x.png', variantLabel: 'Red / M' }

const apiError = (detail: string) => new ApiErrorImpl(500, { type: 'about:blank', title: 'Server error', status: 500, detail })

function row(productId: string, extra: Partial<WishlistItemResponse> = {}): WishlistItemResponse {
  return {
    productId,
    name: 'Server ' + productId,
    price: '120.50' as unknown as number, // the wire may carry a decimal string
    currency: 'INR',
    imageUrl: null,
    variantId: null,
    variantLabel: null,
    addedAt: '2026-11-17T00:00:00Z',
    ...extra,
  }
}

function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** Let pending promise continuations run (no timers involved). */
const settle = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve()
}

const toasts = () => useUiStore.getState().toasts.map((t) => t.message)
const items = () => useWishlistStore.getState().items
const serverState = (list = items()) => useWishlistStore.setState({ items: list, serverBacked: true })

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  localStorage.clear()
  useUiStore.setState({ toasts: [] })
  useWishlistStore.setState({ items: [], serverBacked: false })
})

afterEach(() => {
  vi.clearAllTimers()
  vi.useRealTimers()
  localStorage.clear()
  useUiStore.setState({ toasts: [] })
  useWishlistStore.setState({ items: [], serverBacked: false })
})

describe('server-backed mutations', () => {
  it('adds optimistically and saves the full snapshot', async () => {
    serverState([])
    api.saveWishlistItem.mockResolvedValue(undefined)
    useWishlistStore.getState().addItem('p1', snap, 'v1')
    expect(items().map((i) => i.productId)).toEqual(['p1'])
    expect(api.saveWishlistItem).toHaveBeenCalledWith({
      productId: 'p1',
      name: 'Shirt',
      price: 499,
      currency: 'INR',
      imageUrl: 'https://img/x.png',
      variantId: 'v1',
      variantLabel: 'Red / M',
    })
    await settle()
    expect(api.getWishlist).not.toHaveBeenCalled()
  })

  it('sends null for missing optional snapshot fields', () => {
    serverState([])
    api.saveWishlistItem.mockResolvedValue(undefined)
    useWishlistStore.getState().addItem('p1', { name: 'A', price: 1, currency: 'USD' })
    expect(api.saveWishlistItem).toHaveBeenCalledWith({
      productId: 'p1',
      name: 'A',
      price: 1,
      currency: 'USD',
      imageUrl: null,
      variantId: null,
      variantLabel: null,
    })
  })

  it('removes optimistically and calls removeWishlistItem(productId, variantId)', () => {
    serverState([
      { productId: 'p1', variantId: 'v1', addedAt: 1, snapshot: snap },
      { productId: 'p1', addedAt: 2, snapshot: snap },
    ])
    api.removeWishlistItem.mockResolvedValue(undefined)
    useWishlistStore.getState().removeItem('p1', 'v1')
    expect(items().map((i) => i.variantId)).toEqual([undefined])
    expect(api.removeWishlistItem).toHaveBeenCalledWith('p1', 'v1')
  })

  it('makes no call and no state change for an add without a snapshot', async () => {
    serverState([])
    useWishlistStore.getState().addItem('p1')
    useWishlistStore.getState().toggleItem('p2')
    await settle()
    expect(items()).toEqual([])
    expect(api.saveWishlistItem).not.toHaveBeenCalled()
  })

  it('keeps a single entry when adding an item that is already there', () => {
    serverState([{ productId: 'p1', addedAt: 1, snapshot: snap }])
    api.saveWishlistItem.mockResolvedValue(undefined)
    useWishlistStore.getState().addItem('p1', snap)
    expect(items()).toHaveLength(1)
  })

  it('clearWishlist removes every row on the server', () => {
    serverState([
      { productId: 'p1', addedAt: 1, snapshot: snap },
      { productId: 'p2', variantId: 'v2', addedAt: 2, snapshot: snap },
    ])
    api.removeWishlistItem.mockResolvedValue(undefined)
    useWishlistStore.getState().clearWishlist()
    expect(items()).toEqual([])
    expect(api.removeWishlistItem).toHaveBeenCalledWith('p1', undefined)
    expect(api.removeWishlistItem).toHaveBeenCalledWith('p2', 'v2')
  })
})

describe('failure and reconcile', () => {
  it('notifies and reconciles from getWishlist when a save fails', async () => {
    serverState([])
    api.saveWishlistItem.mockRejectedValue(apiError('Wishlist is full'))
    api.getWishlist.mockResolvedValue([row('srv1', { variantId: 'v9', imageUrl: 'https://i/1.png', variantLabel: 'L' })])
    useWishlistStore.getState().addItem('p1', snap)
    expect(items().map((i) => i.productId)).toEqual(['p1'])
    await settle()
    expect(toasts()).toEqual(['Wishlist is full'])
    expect(api.getWishlist).toHaveBeenCalledTimes(1)
    expect(items()).toEqual([
      {
        productId: 'srv1',
        variantId: 'v9',
        addedAt: Date.parse('2026-11-17T00:00:00Z'),
        snapshot: { name: 'Server srv1', price: 120.5, currency: 'INR', imageUrl: 'https://i/1.png', variantLabel: 'L' },
      },
    ])
  })

  it('rolls back to the pre-mutation items when the reload also fails', async () => {
    const before = [{ productId: 'p0', addedAt: 1, snapshot: snap }]
    serverState(before)
    api.saveWishlistItem.mockRejectedValue(new Error('offline'))
    api.getWishlist.mockRejectedValue(new Error('offline'))
    useWishlistStore.getState().addItem('p1', snap)
    expect(items()).toHaveLength(2)
    await settle()
    expect(items()).toEqual(before)
    expect(toasts()).toHaveLength(1)
  })

  it('rolls back a failed remove as well', async () => {
    const before = [{ productId: 'p0', addedAt: 1, snapshot: snap }]
    serverState(before)
    api.removeWishlistItem.mockRejectedValue(new Error('offline'))
    api.getWishlist.mockRejectedValue(new Error('offline'))
    useWishlistStore.getState().removeItem('p0')
    expect(items()).toEqual([])
    await settle()
    expect(items()).toEqual(before)
  })

  it('discards a reconcile that a later mutation superseded', async () => {
    serverState([])
    const reload = deferred<WishlistItemResponse[]>()
    api.saveWishlistItem.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    api.getWishlist.mockReturnValue(reload.promise)
    useWishlistStore.getState().addItem('p1', snap)
    await settle()
    expect(api.getWishlist).toHaveBeenCalledTimes(1)
    // a later mutation starts while the reload is still in flight
    useWishlistStore.getState().addItem('p2', snap)
    reload.resolve([row('stale')])
    await settle()
    expect(items().map((i) => i.productId)).toEqual(['p1', 'p2'])
  })

  it('discards a failed reload that a later mutation superseded', async () => {
    serverState([])
    const reload = deferred<WishlistItemResponse[]>()
    api.saveWishlistItem.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    api.getWishlist.mockReturnValue(reload.promise)
    useWishlistStore.getState().addItem('p1', snap)
    await settle()
    useWishlistStore.getState().addItem('p2', snap)
    reload.reject(new Error('offline'))
    await settle()
    expect(items().map((i) => i.productId)).toEqual(['p1', 'p2'])
  })
})

describe('attachServer', () => {
  const local = (n: number) =>
    Array.from({ length: n }, (_, i) => ({ productId: 'p' + i, addedAt: i, snapshot: snap }))

  it('merges anonymous items with snapshots and adopts the server rows', async () => {
    useWishlistStore.setState({
      items: [
        { productId: 'p1', variantId: 'v1', addedAt: 1, snapshot: snap },
        { productId: 'nosnap', addedAt: 2 },
      ],
      serverBacked: false,
    })
    api.mergeWishlist.mockResolvedValue([row('p1', { variantId: 'v1' }), row('p9')])
    await useWishlistStore.getState().attachServer()
    expect(api.mergeWishlist).toHaveBeenCalledTimes(1)
    expect(api.mergeWishlist).toHaveBeenCalledWith([
      {
        productId: 'p1',
        name: 'Shirt',
        price: 499,
        currency: 'INR',
        imageUrl: 'https://img/x.png',
        variantId: 'v1',
        variantLabel: 'Red / M',
      },
    ])
    expect(api.getWishlist).not.toHaveBeenCalled()
    expect(useWishlistStore.getState().serverBacked).toBe(true)
    expect(items()).toHaveLength(2)
    expect(items()[0]).toMatchObject({
      productId: 'p1',
      variantId: 'v1',
      snapshot: { price: 120.5, imageUrl: undefined, variantLabel: undefined },
    })
    expect(typeof items()[0]?.snapshot?.price).toBe('number')
    expect(items()[1]?.variantId).toBeUndefined()
  })

  it('sends 250 items as two merge calls of 200 and 50', async () => {
    useWishlistStore.setState({ items: local(250), serverBacked: false })
    api.mergeWishlist.mockResolvedValueOnce([row('first')]).mockResolvedValueOnce([row('last')])
    await useWishlistStore.getState().attachServer()
    expect(api.mergeWishlist).toHaveBeenCalledTimes(2)
    expect(api.mergeWishlist.mock.calls[0]?.[0]).toHaveLength(200)
    expect(api.mergeWishlist.mock.calls[1]?.[0]).toHaveLength(50)
    expect(api.mergeWishlist.mock.calls[1]?.[0][0].productId).toBe('p200')
    // the last chunk's response is the final server list
    expect(items().map((i) => i.productId)).toEqual(['last'])
    expect(useWishlistStore.getState().serverBacked).toBe(true)
  })

  it('does not send items without a snapshot and loads via getWishlist instead', async () => {
    useWishlistStore.setState({ items: [{ productId: 'nosnap', addedAt: 1 }], serverBacked: false })
    api.getWishlist.mockResolvedValue([row('srv')])
    await useWishlistStore.getState().attachServer()
    expect(api.mergeWishlist).not.toHaveBeenCalled()
    expect(items().map((i) => i.productId)).toEqual(['srv'])
    expect(useWishlistStore.getState().serverBacked).toBe(true)
  })

  it('loads via getWishlist when there are no local items', async () => {
    api.getWishlist.mockResolvedValue([row('srv')])
    await useWishlistStore.getState().attachServer()
    expect(api.mergeWishlist).not.toHaveBeenCalled()
    expect(items().map((i) => i.productId)).toEqual(['srv'])
    expect(useWishlistStore.getState().serverBacked).toBe(true)
  })

  it('does not merge again when already server-backed', async () => {
    serverState([{ productId: 'p1', addedAt: 1, snapshot: snap }])
    api.getWishlist.mockResolvedValue([row('srv')])
    await useWishlistStore.getState().attachServer()
    expect(api.mergeWishlist).not.toHaveBeenCalled()
    expect(items().map((i) => i.productId)).toEqual(['srv'])
  })

  it('keeps local items, stays anonymous and notifies when the merge fails', async () => {
    const mine = local(2)
    useWishlistStore.setState({ items: mine, serverBacked: false })
    api.mergeWishlist.mockRejectedValue(apiError('Merge refused'))
    await useWishlistStore.getState().attachServer()
    expect(toasts()).toEqual(['Merge refused'])
    expect(items()).toEqual(mine)
    expect(useWishlistStore.getState().serverBacked).toBe(false)
    expect(api.getWishlist).not.toHaveBeenCalled()
  })

  it('becomes server-backed with the retried list when a load fails with no local items', async () => {
    api.getWishlist.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([row('srv')])
    await useWishlistStore.getState().attachServer()
    expect(toasts()).toHaveLength(1)
    expect(api.getWishlist).toHaveBeenCalledTimes(2)
    expect(items().map((i) => i.productId)).toEqual(['srv'])
    expect(useWishlistStore.getState().serverBacked).toBe(true)
  })

  it('becomes server-backed with an empty list when the retry fails too', async () => {
    api.getWishlist.mockRejectedValue(new Error('offline'))
    await useWishlistStore.getState().attachServer()
    expect(items()).toEqual([])
    expect(useWishlistStore.getState().serverBacked).toBe(true)
  })
})

describe('detachServer and persistence', () => {
  it('detachServer clears items and leaves server-backed mode', () => {
    serverState([{ productId: 'p1', addedAt: 1, snapshot: snap }])
    useWishlistStore.getState().detachServer()
    expect(items()).toEqual([])
    expect(useWishlistStore.getState().serverBacked).toBe(false)
  })

  it('detachServer discards an in-flight reconcile', async () => {
    serverState([])
    const reload = deferred<WishlistItemResponse[]>()
    api.saveWishlistItem.mockRejectedValue(new Error('offline'))
    api.getWishlist.mockReturnValue(reload.promise)
    useWishlistStore.getState().addItem('p1', snap)
    await settle()
    useWishlistStore.getState().detachServer()
    reload.resolve([row('stale')])
    await settle()
    expect(items()).toEqual([])
  })

  it('persists a server-backed list as an empty items array and never persists serverBacked', () => {
    api.saveWishlistItem.mockResolvedValue(undefined)
    serverState([])
    useWishlistStore.getState().addItem('p1', snap)
    const saved = JSON.parse(localStorage.getItem('wishlist-store') ?? '{}')
    expect(saved.state).toEqual({ items: [] })
    expect(saved.state).not.toHaveProperty('serverBacked')
  })

  it('persists the anonymous list again after detach', () => {
    serverState([{ productId: 'p1', addedAt: 1, snapshot: snap }])
    useWishlistStore.getState().detachServer()
    useWishlistStore.getState().addItem('p2', snap)
    const saved = JSON.parse(localStorage.getItem('wishlist-store') ?? '{}')
    expect(saved.state.items.map((i: { productId: string }) => i.productId)).toEqual(['p2'])
  })
})
