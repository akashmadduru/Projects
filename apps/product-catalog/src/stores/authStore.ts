/**
 * Zustand auth store. The access token lives in lib/http (memory only); the refresh token is an
 * HttpOnly cookie. `bootstrap` restores a session on page load.
 */

import { create } from 'zustand'
import { onSessionExpired, refreshSession, setAccessToken, takeMfaChallenge } from '../lib/http'
import { authApi } from '../services/authApi'
import type { AuthUser, LoginResult } from '../types'
import { useCartStore } from './cartStore'
import { useUiStore } from './uiStore'
import { useWishlistStore } from './wishlistStore'

export type AuthStatus = 'unknown' | 'anonymous' | 'authenticated'

interface AuthState {
  status: AuthStatus
  user: AuthUser | null
  /** Pending second-factor challenge (token valid ~5 minutes), set instead of a session when 2FA is on. */
  mfaToken: string | null
  /** Resolves 'mfa' when a second factor is still required (see verifyMfa). */
  login: (email: string, password: string) => Promise<'ok' | 'mfa'>
  /** Phone + one-time code sign-in. */
  loginWithOtp: (phone: string, code: string) => Promise<'ok' | 'mfa'>
  /** Completes a pending MFA challenge with a TOTP code or a one-time recovery code. */
  verifyMfa: (input: { code: string } | { recoveryCode: string }) => Promise<void>
  cancelMfa: () => void
  /** Re-reads /me (e.g. after verifying email or phone). */
  refreshUser: () => Promise<void>
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

async function completeLogin(set: (partial: Partial<AuthState>) => void, result: LoginResult): Promise<'ok' | 'mfa'> {
  if ('mfaRequired' in result) {
    set({ mfaToken: result.mfaToken })
    return 'mfa'
  }
  setAccessToken(result.accessToken)
  try {
    await enterAuthenticated(set, await authApi.me())
    set({ mfaToken: null })
    return 'ok'
  } catch (error) {
    setAccessToken(null)
    throw error
  }
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
  mfaToken: null,

  login: async (email, password) => completeLogin(set, await authApi.login({ email, password })),

  loginWithOtp: async (phone, code) => completeLogin(set, await authApi.verifyOtp(phone, code)),

  verifyMfa: async (input) => {
    const mfaToken = get().mfaToken
    if (!mfaToken) throw new Error('No sign-in is waiting for a second factor.')
    await completeLogin(set, await authApi.verifyMfa(mfaToken, input))
  },

  cancelMfa: () => set({ mfaToken: null }),

  refreshUser: async () => {
    if (get().status !== 'authenticated') return
    try {
      set({ user: await authApi.me() })
    } catch {
      // Keep the cached user; the next navigation or request will surface a real session problem.
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
        // Google sign-in with 2FA: refresh answers with a challenge instead of tokens.
        set({ status: 'anonymous', user: null, mfaToken: takeMfaChallenge() })
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
