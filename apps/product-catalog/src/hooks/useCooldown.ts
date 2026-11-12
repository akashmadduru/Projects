/**
 * Counts down from `start()` once per second; `remaining` is 0 when idle. Used for resend timers.
 */

import { useCallback, useEffect, useState } from 'react'

export function useCooldown(): { remaining: number; start: (seconds: number) => void } {
  const [remaining, setRemaining] = useState(0)

  useEffect(() => {
    if (remaining <= 0) return
    const timer = setTimeout(() => setRemaining((r) => r - 1), 1000)
    return () => clearTimeout(timer)
  }, [remaining])

  const start = useCallback((seconds: number) => setRemaining(seconds), [])
  return { remaining, start }
}
