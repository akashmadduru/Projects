/**
 * Custom hook for theme management
 */

import { useThemeStore } from '../stores'

export function useTheme() {
  const mode = useThemeStore((state) => state.mode)
  const setMode = useThemeStore((state) => state.setMode)
  const toggle = useThemeStore((state) => state.toggle)

  return {
    mode,
    setMode,
    toggle,
  }
}
