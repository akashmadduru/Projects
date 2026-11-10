/**
 * Theme system - CSS custom property constants and utilities
 */

export type ThemeMode = 'light' | 'dark'

export const THEME_MODES = ['light', 'dark'] as const

export const CSS_VARS = {
  // Named colors
  primary: '--color-primary',
  secondary: '--color-secondary',
  surface: '--color-surface',
  'surface-variant': '--color-surface-variant',
  outline: '--color-outline',
  error: '--color-error',
  'on-primary': '--color-on-primary',
  'on-secondary': '--color-on-secondary',
  'on-surface': '--color-on-surface',
  'on-error': '--color-on-error',
} as const

export const LIGHT_PALETTE = {
  primary: '220 90% 56%',
  secondary: '221 85% 48%',
  surface: '0 0% 100%',
  'surface-variant': '220 13% 91%',
  outline: '220 9% 46%',
  error: '0 100% 50%',
  'on-primary': '0 0% 100%',
  'on-secondary': '0 0% 100%',
  'on-surface': '220 13% 13%',
  'on-error': '0 0% 100%',
} as const

export const DARK_PALETTE = {
  primary: '220 95% 70%',
  secondary: '221 90% 72%',
  surface: '220 13% 13%',
  'surface-variant': '220 13% 23%',
  outline: '220 8% 70%',
  error: '0 100% 71%',
  'on-primary': '220 100% 6%',
  'on-secondary': '220 100% 6%',
  'on-surface': '220 13% 98%',
  'on-error': '0 100% 6%',
} as const

export const PALETTES = {
  light: LIGHT_PALETTE,
  dark: DARK_PALETTE,
} as const

export function getSystemTheme(): ThemeMode {
  if (typeof window === 'undefined') return 'light'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}
