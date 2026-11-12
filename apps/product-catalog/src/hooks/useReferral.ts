/**
 * Referral capture: a `?ref=CODE` on any URL (the share link lands on /register) is remembered, then
 * claimed once the user is signed in. A rejected claim (own code, already claimed, unknown) is dropped.
 */

import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { engageApi } from '../services/engageApi'
import { useAuthStore } from '../stores'

const KEY = 'referral-code'

function read(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function useReferral(): void {
  const { search } = useLocation()
  const authenticated = useAuthStore((s) => s.status === 'authenticated')

  useEffect(() => {
    const ref = new URLSearchParams(search).get('ref')?.trim().toUpperCase()
    if (!ref || !/^[A-Z0-9]{4,16}$/.test(ref)) return
    try {
      localStorage.setItem(KEY, ref)
    } catch {
      // Storage unavailable: the code simply is not remembered across the sign-up.
    }
  }, [search])

  useEffect(() => {
    if (!authenticated) return
    const code = read()
    if (!code) return
    void engageApi
      .claimReferral(code)
      .catch(() => undefined)
      .finally(() => {
        try {
          localStorage.removeItem(KEY)
        } catch {
          // Nothing to clean up.
        }
      })
  }, [authenticated])
}
