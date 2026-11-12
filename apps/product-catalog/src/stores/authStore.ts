/**
 * Zustand auth store. The access token lives in lib/http (memory only); the refresh token is an
 * HttpOnly cookie. `bootstrap` restores a session on page load.
 */

import { create } from 'zustand'
import { onSessionExpired, refreshSession, setAccessToken } from '../lib/http'
import { authApi } from '../services/authApi'
import type { AuthUser } from '../types'
import { useCartStore } from './cartStore'
import { useUiStore } from './uiStore'
import { useWishlistStore } from './wishlistStore'

export type AuthStatus = 'unknown' | 'anonymous' | 'authenticated'

interface AuthState {
  status: AuthStatus
  user: AuthUser | null
  login: (email: string, password: string) => Promise<void>
  /** Creates the account, then signs in. */
  register: (email: string, password: string, displayName: string) => Promise<void>
  logout: () => Promise<void>
  bootstrap: () => Promise<void>
  /** Local-only sign-out for when the session can no longer be recovered. */
  forceLogout: () => void
  /** Reflects an edit made elsewhere (e.g. a saved display name) without refetching the session. */
  patchUser: (patch: Partial<AuthUser>) => void
}

let bootstrapPromise: Promise<void> | null = null

async function enterAuthenticated(
  set: (partial: Partial<AuthState>) => void,
  user: AuthUser,
): Promise<void> {
  // Attach (merge local cart/wishlist) before flipping to 'authenticated' so guards and the
  // checkout flow never see a half-synced cart. Attach failures must not block login.
  await Promise.allSettled([useCartStore.getState().attachServer(), useWishlistStore.getState().attachServer()])
  set({ status: 'authenticated', user })
}

function enterAnonymous(set: (partial: Partial<AuthState>) => void): void {
  setAccessToken(null)
  useCartStore.getState().detachServer()
  useWishlistStore.getState().detachServer()
  set({ status: 'anonymous', user: null })
}

export const useAuthStore = create<AuthState>()((set, get) => ({
  status: 'unknown',
  user: null,

  login: async (email, password) => {
    const token = await authApi.login({ email, password })
    setAccessToken(token.accessToken)
    try {
      await enterAuthenticated(set, await authApi.me())
    } catch (error) {
      setAccessToken(null)
      throw error
    }
  },

  register: async (email, password, displayName) => {
    await authApi.register({ email, password, displayName })
    await get().login(email, password)
  },

  logout: async () => {
    try {
      await authApi.logout()
    } catch {
      // The local session is dropped regardless of whether the server call succeeded.
    }
    enterAnonymous(set)
  },

  bootstrap: () => {
    if (bootstrapPromise) return bootstrapPromise
    bootstrapPromise = (async () => {
      const token = await refreshSession()
      if (!token) {
        set({ status: 'anonymous', user: null })
        return
      }
      try {
        await enterAuthenticated(set, await authApi.me())
      } catch {
        enterAnonymous(set)
      }
    })()
    return bootstrapPromise
  },

  forceLogout: () => {
    enterAnonymous(set)
  },

  patchUser: (patch) => {
    const user = get().user
    if (user) set({ user: { ...user, ...patch } })
  },
}))

// A 401 that refreshing could not fix means the session is gone: sign out and send the user to login.
onSessionExpired(() => {
  const { status, forceLogout } = useAuthStore.getState()
  if (status !== 'authenticated') return
  forceLogout()
  useUiStore.getState().notify('Your session has expired. Please sign in again.')
  const next = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.assign(`/login?next=${next}`)
})
