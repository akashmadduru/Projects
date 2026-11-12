/**
 * Circular progress gauge for profile completeness. The arc eases to its value on mount.
 */

import { useEffect, useState } from 'react'

const RADIUS = 42
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

export function CompletenessRing({ percent, className = 'h-28 w-28' }: { percent: number; className?: string }) {
  // Start empty, then set the real value on the next frame so the CSS transition has a start point.
  const [shown, setShown] = useState(0)
  useEffect(() => {
    const frame = requestAnimationFrame(() => setShown(percent))
    return () => cancelAnimationFrame(frame)
  }, [percent])

  const complete = percent >= 100
  return (
    <div
      role="img"
      aria-label={`Profile ${percent}% complete`}
      className={`relative shrink-0 ${className}`}
    >
      <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90">
        <circle cx="50" cy="50" r={RADIUS} fill="none" stroke="hsl(var(--color-outline) / 0.25)" strokeWidth="10" />
        <circle
          cx="50"
          cy="50"
          r={RADIUS}
          fill="none"
          stroke={complete ? 'hsl(var(--color-vivid-amber))' : 'hsl(var(--color-vivid-teal))'}
          strokeWidth="10"
          strokeLinecap="round"
          strokeDasharray={CIRCUMFERENCE}
          strokeDashoffset={CIRCUMFERENCE * (1 - shown / 100)}
          style={{ transition: 'stroke-dashoffset 1.4s cubic-bezier(0.22, 1, 0.36, 1), stroke 0.6s ease' }}
        />
      </svg>
      <span className="font-display absolute inset-0 flex items-center justify-center text-2xl text-on-surface">
        {percent}
        <span className="text-sm">%</span>
      </span>
    </div>
  )
}
