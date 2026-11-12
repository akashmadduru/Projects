/**
 * Custom hook to access HTTP client and auth
 */

import { apiFetch, getAccessToken } from '../lib/http'

export function useApi() {
  return {
    fetch: apiFetch,
    getAuthToken: getAccessToken,
  }
}
