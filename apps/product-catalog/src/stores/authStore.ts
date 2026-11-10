/**
 * Zustand auth store - JWT token management
 */

import { create } from 'zustand'
import { subscribeWithSelector } from 'zustand/middleware'
import { getAuthToken, setAuthToken } from '../lib/http'

interface AuthState {
  token: string | null
  setToken: (token: string | null) => void
  isAuthenticated: boolean
}

export const useAuthStore = create<AuthState>()(
  subscribeWithSelector((set) => {
    const initialToken = getAuthToken()

    return {
      token: initialToken,
      isAuthenticated: initialToken !== null,
      setToken: (token: string | null) => {
        setAuthToken(token)
        set({
          token,
          isAuthenticated: token !== null,
        })
      },
    }
  }),
)
