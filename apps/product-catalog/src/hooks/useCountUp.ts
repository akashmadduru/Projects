/**
 * Eases a number up from 0 to `target`. Jumps straight to the target when the user prefers
 * reduced motion, so no one waits on a counter.
 */

import { useEffect, useState } from 'react'

export function useCountUp(target: number, durationMs = 1500): number {
  const [value, setValue] = useState(0)

  useEffect(() => {
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    if (reduced || durationMs <= 0 || target === 0) {
      setValue(target)
      return
    }
    let frame = 0
    const start = performance.now()
    const tick = (now: number) => {
      const progress = Math.min((now - start) / durationMs, 1)
      const eased = 1 - Math.pow(1 - progress, 3)
      setValue(target * eased)
      if (progress < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [target, durationMs])

  return value
}
