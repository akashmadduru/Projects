/**
 * Auth store (TD-TEST-02): login, the second-factor branch, cart/wishlist attach on sign-in,
 * logout, and session restore. authApi and the refresh call are mocked; the token holder in
 * lib/http is real.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest'

const authApi = vi.hoisted(() => ({
  login: vi.fn(),
  verifyOtp: vi.fn(),
  verifyMfa: vi.fn(),
  me: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
}))
vi.mock('../services/authApi', () => ({ authApi }))

const refresh = vi.hoisted(() => ({ refreshSession: vi.fn(), takeMfaChallenge: vi.fn() }))
vi.mock('../lib/http', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/http')>()),
  refreshSession: refresh.refreshSession,
  takeMfaChallenge: refresh.takeMfaChallenge,
}))

import { getAccessToken, setAccessToken } from '../lib/http'
import { useAuthStore } from './authStore'
import { useCartStore } from './cartStore'
import { useWishlistStore } from './wishlistStore'

const USER = { id: 'u1', email: 'a@b.c', displayName: 'A', roles: ['VIEWER'] }

let order: string[]

beforeEach(() => {
  vi.resetAllMocks()
  setAccessToken(null)
  order = []
  useAuthStore.setState({ status: 'anonymous', user: null, mfaToken: null })
  vi.spyOn(useCartStore.getState(), 'attachServer').mockImplementation(async () => {
    order.push('cart')
  })
  vi.spyOn(useWishlistStore.getState(), 'attachServer').mockImplementation(async () => {
    order.push('wishlist')
  })
  vi.spyOn(useCartStore.getState(), 'detachServer').mockImplementation(() => {
    order.push('cart-detach')
  })
  vi.spyOn(useWishlistStore.getState(), 'detachServer').mockImplementation(() => undefined)
})

describe('login', () => {
  it('stores the token, syncs cart and wishlist, then becomes authenticated', async () => {
    authApi.login.mockResolvedValue({ accessToken: 'tok' })
    authApi.me.mockImplementation(async () => {
      order.push('me')
      return USER
    })

    await expect(useAuthStore.getState().login('a@b.c', 'pw')).resolves.toBe('ok')

    expect(getAccessToken()).toBe('tok')
    expect(useAuthStore.getState()).toMatchObject({ status: 'authenticated', user: USER, mfaToken: null })
    expect(order.slice(0, 1)).toEqual(['me'])
    expect(order).toEqual(expect.arrayContaining(['cart', 'wishlist']))
  })

  it('a second factor leaves the user signed out with a pending challenge', async () => {
    authApi.login.mockResolvedValue({ mfaRequired: true, mfaToken: 'mfa-1' })

    await expect(useAuthStore.getState().login('a@b.c', 'pw')).resolves.toBe('mfa')

    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', mfaToken: 'mfa-1' })
    expect(getAccessToken()).toBeNull()
    expect(order).toEqual([])
  })

  it('drops the token again if the profile cannot be loaded', async () => {
    authApi.login.mockResolvedValue({ accessToken: 'tok' })
    authApi.me.mockRejectedValue(new Error('auth-service down'))

    await expect(useAuthStore.getState().login('a@b.c', 'pw')).rejects.toThrow('auth-service down')

    expect(getAccessToken()).toBeNull()
    expect(useAuthStore.getState().status).toBe('anonymous')
  })

  it('a failing cart sync does not block sign-in', async () => {
    authApi.login.mockResolvedValue({ accessToken: 'tok' })
    authApi.me.mockResolvedValue(USER)
    vi.spyOn(useCartStore.getState(), 'attachServer').mockRejectedValue(new Error('cart down'))

    await expect(useAuthStore.getState().login('a@b.c', 'pw')).resolves.toBe('ok')
    expect(useAuthStore.getState().status).toBe('authenticated')
  })

  it('completing the second factor signs the user in', async () => {
    useAuthStore.setState({ mfaToken: 'mfa-1' })
    authApi.verifyMfa.mockResolvedValue({ accessToken: 'tok2' })
    authApi.me.mockResolvedValue(USER)

    await useAuthStore.getState().verifyMfa({ code: '123456' })

    expect(authApi.verifyMfa).toHaveBeenCalledWith('mfa-1', { code: '123456' })
    expect(useAuthStore.getState()).toMatchObject({ status: 'authenticated', mfaToken: null })
  })
})

describe('logout', () => {
  it('signs out locally even when the server call fails', async () => {
    setAccessToken('tok')
    useAuthStore.setState({ status: 'authenticated', user: USER as never })
    authApi.logout.mockRejectedValue(new Error('offline'))

    await useAuthStore.getState().logout()

    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', user: null })
    expect(getAccessToken()).toBeNull()
    expect(order).toContain('cart-detach')
  })
})

describe('bootstrap', () => {
  it('without a session but with a pending Google 2FA challenge, keeps the challenge', async () => {
    vi.resetModules()
    const http = await import('../lib/http')
    vi.mocked(http.refreshSession).mockResolvedValue(null)
    vi.mocked(http.takeMfaChallenge).mockReturnValue('mfa-google')
    const { useAuthStore: fresh } = await import('./authStore')

    await fresh.getState().bootstrap()

    expect(fresh.getState()).toMatchObject({ status: 'anonymous', mfaToken: 'mfa-google' })
  })
})
