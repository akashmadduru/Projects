/**
 * "Ends in 02h 14m 09s" timer. Ticks once a second; deliberately not an aria-live region
 * (it would chatter), only a role="timer" that assistive tech can read on demand.
 */

import { useEffect, useState } from 'react'

function parts(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const d = Math.floor(total / 86400)
  const h = Math.floor((total % 86400) / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d > 0 ? `${d}d ` : ''}${pad(h)}h ${pad(m)}m ${pad(s)}s`
}

export function Countdown({
  endsAt,
  className = '',
  prefix = 'Ends in ',
}: {
  endsAt: string
  className?: string
  /** Text before the time; pass '' when the surrounding copy already says it. */
  prefix?: string
}) {
  const end = Date.parse(endsAt)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])
  const left = end - now
  return (
    <span role="timer" className={`tabular-nums ${className}`}>
      {left > 0 ? `${prefix}${parts(left)}` : 'Deal ended'}
    </span>
  )
}
