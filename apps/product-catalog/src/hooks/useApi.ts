/**
 * Custom hook to access HTTP client and auth
 */

import { useCallback } from 'react'
import { apiFetch, setAuthToken, getAuthToken } from '../lib/http'
import { useAuthStore } from '../stores'

export function useApi() {
  const setToken = useAuthStore((state) => state.setToken)

  const setAuthTokenCallback = useCallback(
    (token: string | null) => {
      setAuthToken(token)
      setToken(token)
    },
    [setToken],
  )

  return {
    fetch: apiFetch,
    setAuthToken: setAuthTokenCallback,
    getAuthToken,
  }
}
