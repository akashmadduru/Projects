/**
 * Initials avatar: a stable per-person color on an ink-outlined disc
 */

import { avatarHue, initialsOf, type AvatarHue } from '../../lib/account'

type Size = 'sm' | 'md' | 'lg' | 'xl'

const SIZES: Record<Size, string> = {
  sm: 'h-8 w-8 text-xs border-2',
  md: 'h-10 w-10 text-sm border-2',
  lg: 'h-16 w-16 text-xl border-[3px]',
  xl: 'h-24 w-24 text-3xl border-[3px]',
}

// Vivid hues read best with dark initials on the light ones, white on the rest.
const DARK_TEXT: ReadonlySet<AvatarHue> = new Set(['vivid-amber', 'vivid-lime'])

interface Props {
  name?: string | null
  email: string
  size?: Size
  className?: string
}

export function Avatar({ name, email, size = 'md', className = '' }: Props) {
  const hue = avatarHue(email)
  const text = DARK_TEXT.has(hue) ? 'text-ink' : 'text-white dark:text-ink'
  return (
    <span
      aria-hidden="true"
      className={`font-display inline-flex shrink-0 select-none items-center justify-center rounded-full border-ink shadow-[3px_3px_0_hsl(var(--color-ink))] ${SIZES[size]} ${text} ${className}`}
      style={{ background: `hsl(var(--color-${hue}))` }}
    >
      {initialsOf(name, email)}
    </span>
  )
}
