/**
 * Cartwise space-age wordmark: a cart riding an atomic orbit.
 */

import { Link } from 'react-router-dom'

export function LogoMark({ className = 'w-9 h-9', orbitClassName = '' }: { className?: string; orbitClassName?: string }) {
  return (
    <svg viewBox="0 0 40 40" className={className} aria-hidden="true">
      <circle cx="20" cy="20" r="17" fill="hsl(var(--color-accent))" stroke="hsl(var(--color-ink))" strokeWidth="2" />
      <g className={orbitClassName} style={{ transformOrigin: '20px 20px' }}>
        <ellipse
          cx="20"
          cy="20"
          rx="19"
          ry="7"
          fill="none"
          stroke="hsl(var(--color-on-brand))"
          strokeWidth="2"
          transform="rotate(-25 20 20)"
        />
        <circle cx="37" cy="12" r="2.6" fill="hsl(var(--color-vivid-amber))" stroke="hsl(var(--color-ink))" strokeWidth="1.2" />
      </g>
      <path
        d="M11 13h2.2l2 8.6a1.2 1.2 0 0 0 1.2.9h6.8a1.2 1.2 0 0 0 1.2-.9L26 16H14.3"
        fill="none"
        stroke="hsl(var(--color-ink))"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="17" cy="26.5" r="1.5" fill="hsl(var(--color-ink))" />
      <circle cx="23" cy="26.5" r="1.5" fill="hsl(var(--color-ink))" />
    </svg>
  )
}

export function Logo() {
  return (
    <Link
      to="/"
      className="group flex shrink-0 items-center gap-2 rounded-full text-on-brand focus-visible:outline-on-brand"
      aria-label="Cartwise home"
    >
      <LogoMark
        className="w-10 h-10 drop-shadow-[2px_2px_0_hsl(var(--color-ink))] transition-transform duration-700 ease-[cubic-bezier(0.34,1.56,0.64,1)] group-hover:-rotate-12 group-hover:scale-110"
        orbitClassName="transition-transform duration-1000 group-hover:rotate-[200deg]"
      />
      <span className="font-display text-2xl leading-none tracking-wide [text-shadow:2px_2px_0_hsl(var(--color-ink))]">
        Cart<span className="text-accent">wise</span>
      </span>
    </Link>
  )
}
