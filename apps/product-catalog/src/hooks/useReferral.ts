/**
 * Referral capture: a `?ref=CODE` on any URL (the share link lands on /register) is remembered, then
 * claimed once the user is signed in. The outcome is shown as a toast; a rejected claim (own code,
 * already claimed, unknown) is then dropped.
 */

import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { engageApi } from '../services/engageApi'
import { useAuthStore, useUiStore } from '../stores'

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
    // Tell the customer what happened (TD-P8-13); the code is dropped either way.
    const notify = useUiStore.getState().notify
    void engageApi
      .claimReferral(code)
      .then(() => notify('Referral code applied: you both get coins after your first delivered order'))
      .catch((e: unknown) => {
        const detail = e instanceof Error && e.message ? ` (${e.message})` : ''
        notify(`Referral code ${code} could not be applied${detail}`)
      })
      .finally(() => {
        try {
          localStorage.removeItem(KEY)
        } catch {
          // Nothing to clean up.
        }
      })
  }, [authenticated])
}
