/**
 * Light/dark toggle, styled for the brand bar. The icon spins in on every change.
 */

import { useTheme } from '../hooks'

export function ThemeToggle() {
  const { mode, toggle } = useTheme()
  const next = mode === 'light' ? 'dark' : 'light'

  return (
    <button
      type="button"
      onClick={toggle}
      className="press rounded-full border-2 border-transparent p-2 text-on-brand hover:border-on-brand/40 hover:bg-on-brand/10"
      aria-label={`Switch to ${next} mode`}
      title={`Switch to ${next} mode`}
    >
      <span key={mode} className="block animate-[pop_0.5s_cubic-bezier(0.34,1.56,0.64,1)]">
        {mode === 'light' ? (
          <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        ) : (
          <svg className="w-5 h-5 text-accent" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <circle cx="12" cy="12" r="4" />
            <path
              d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
              strokeLinecap="round"
            />
          </svg>
        )}
      </span>
    </button>
  )
}
