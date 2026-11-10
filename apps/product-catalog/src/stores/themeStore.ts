/**
 * Zustand theme store - light/dark mode management with localStorage persistence
 */

import { create } from 'zustand'
import { subscribeWithSelector } from 'zustand/middleware'
import type { ThemeMode } from '../lib/theme'
import { getSystemTheme } from '../lib/theme'

interface ThemeState {
  mode: ThemeMode
  setMode: (mode: ThemeMode) => void
  toggle: () => void
}

function getInitialTheme(): ThemeMode {
  if (typeof window === 'undefined') return 'light'

  const stored = localStorage.getItem('theme_mode')
  if (stored === 'light' || stored === 'dark') {
    return stored
  }

  return getSystemTheme()
}

export const useThemeStore = create<ThemeState>()(
  subscribeWithSelector((set) => {
    const initialTheme = getInitialTheme()

    // Apply theme to document on init
    if (typeof window !== 'undefined') {
      document.documentElement.setAttribute('data-theme', initialTheme)
    }

    return {
      mode: initialTheme,
      setMode: (mode: ThemeMode) => {
        set({ mode })
        localStorage.setItem('theme_mode', mode)
        if (typeof window !== 'undefined') {
          document.documentElement.setAttribute('data-theme', mode)
        }
      },
      toggle: () => {
        set((state) => {
          const newMode = state.mode === 'light' ? 'dark' : 'light'
          localStorage.setItem('theme_mode', newMode)
          if (typeof window !== 'undefined') {
            document.documentElement.setAttribute('data-theme', newMode)
          }
          return { mode: newMode }
        })
      },
    }
  }),
)
