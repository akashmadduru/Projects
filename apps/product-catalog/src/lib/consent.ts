/** Cookie consent: necessary is always on; analytics (view beacon, recently-viewed sync) is opt-in. */

import { create } from 'zustand'

const KEY = 'cookie-consent'

export interface Consent {
  analytics: boolean
  decided: boolean
}

function read(): Consent {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? 'null') as { analytics?: unknown } | null
    if (raw && typeof raw.analytics === 'boolean') return { analytics: raw.analytics, decided: true }
  } catch {
    // Unreadable or unavailable storage: treat as undecided.
  }
  return { analytics: false, decided: false }
}

interface ConsentState extends Consent {
  set: (analytics: boolean) => void
  reopen: () => void
}

export const useConsent = create<ConsentState>()((set) => ({
  ...read(),
  set: (analytics) => {
    try {
      localStorage.setItem(KEY, JSON.stringify({ analytics }))
    } catch {
      // Choice only lasts for this page view.
    }
    set({ analytics, decided: true })
  },
  reopen: () => set({ decided: false }),
}))

export function analyticsAllowed(): boolean {
  return useConsent.getState().analytics
}
