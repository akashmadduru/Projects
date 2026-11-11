/**
 * Space-age decorative motifs: starbursts, orbiting atoms and boomerangs. All purely decorative.
 */

// Classic 1950s starburst: eight long rays alternating with eight short ones.
function starburstPoints(rays: number, outer: number, inner: number): string {
  const points: string[] = []
  for (let i = 0; i < rays * 2; i++) {
    const angle = (Math.PI * i) / rays - Math.PI / 2
    const radius = i % 2 === 0 ? (i % 4 === 0 ? outer : outer * 0.6) : inner
    points.push(`${(50 + radius * Math.cos(angle)).toFixed(2)},${(50 + radius * Math.sin(angle)).toFixed(2)}`)
  }
  return points.join(' ')
}

// Price-flash seal: many short, even points around a solid body that text can sit on.
function sealPoints(points: number, outer: number, inner: number): string {
  return Array.from({ length: points * 2 }, (_, i) => {
    const angle = (Math.PI * i) / points - Math.PI / 2
    const radius = i % 2 === 0 ? outer : inner
    return `${(50 + radius * Math.cos(angle)).toFixed(2)},${(50 + radius * Math.sin(angle)).toFixed(2)}`
  }).join(' ')
}

const STARBURST = starburstPoints(8, 50, 5)
const SPARKLE = starburstPoints(4, 50, 9)
const SEAL = sealPoints(16, 49, 39)

export function Starburst({ className = 'w-8 h-8', sparkle = false }: { className?: string; sparkle?: boolean }) {
  return (
    <svg viewBox="0 0 100 100" className={className} aria-hidden="true">
      <polygon points={sparkle ? SPARKLE : STARBURST} fill="currentColor" />
    </svg>
  )
}

/** Sunburst seal for prices and deal flashes; outlined in ink like a printed sticker. */
export function Seal({ className = 'w-14 h-14' }: { className?: string }) {
  return (
    <svg viewBox="0 0 100 100" className={className} aria-hidden="true">
      <polygon
        points={SEAL}
        fill="currentColor"
        stroke="hsl(var(--color-ink))"
        strokeWidth="3"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/** Three elliptical orbits around a nucleus; the orbit group spins, the nucleus glows. */
export function Atom({ className = 'w-16 h-16', spinning = true }: { className?: string; spinning?: boolean }) {
  return (
    <svg viewBox="0 0 100 100" className={className} aria-hidden="true">
      <g className={spinning ? 'animate-spin-slow' : ''} style={{ transformOrigin: '50px 50px' }}>
        {[0, 60, 120].map((angle) => (
          <ellipse
            key={angle}
            cx="50"
            cy="50"
            rx="44"
            ry="15"
            fill="none"
            stroke="currentColor"
            strokeWidth="3.5"
            transform={`rotate(${angle} 50 50)`}
          />
        ))}
        <circle cx="94" cy="50" r="5" fill="hsl(var(--color-accent))" />
        <circle cx="28" cy="88" r="4.5" fill="hsl(var(--color-vivid-amber))" />
      </g>
      <circle cx="50" cy="50" r="9" fill="hsl(var(--color-accent-2))" stroke="currentColor" strokeWidth="3" />
    </svg>
  )
}

/** Mid-century boomerang (kidney) shape. */
export function Boomerang({ className = 'w-24 h-12' }: { className?: string }) {
  return (
    <svg viewBox="0 0 120 60" className={className} aria-hidden="true">
      <path
        d="M6 46C18 10 48 2 60 22c12 20 42 12 54-12-4 30-30 48-54 34C42 34 20 52 6 46Z"
        fill="currentColor"
      />
    </svg>
  )
}
