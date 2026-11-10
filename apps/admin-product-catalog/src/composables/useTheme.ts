import { computed, ref, type ComputedRef, type Ref } from 'vue';

/**
 * Runtime theme controller.
 *
 * Deliberately does NOT re-derive the initial theme from `localStorage` or
 * `prefers-color-scheme` — the inline bootstrap script in `index.html` already
 * did that before first paint (to avoid a FOUC) and wrote its decision onto
 * `document.documentElement.dataset.theme`. Reading that attribute here, rather
 * than duplicating the fallback logic, is what keeps the two in sync by
 * construction instead of by convention.
 */
export type ThemeName = 'catalogLight' | 'catalogDark';

const THEME_STORAGE_KEY = 'theme';
const DEFAULT_THEME: ThemeName = 'catalogLight';

function isThemeName(value: string | undefined): value is ThemeName {
  return value === 'catalogLight' || value === 'catalogDark';
}

function currentDomTheme(): ThemeName {
  const value = document.documentElement.dataset.theme;
  return isThemeName(value) ? value : DEFAULT_THEME;
}

export interface UseTheme {
  readonly theme: Ref<ThemeName>;
  readonly isDark: ComputedRef<boolean>;
  setTheme(theme: ThemeName): void;
  toggleTheme(): void;
}

// Module-level so every `useTheme()` caller (e.g. AppShell's navbar and
// ThemeToggle) shares one ref instead of drifting out of sync with each other.
const theme = ref<ThemeName>(currentDomTheme());

function applyTheme(next: ThemeName): void {
  theme.value = next;
  document.documentElement.dataset.theme = next;
  localStorage.setItem(THEME_STORAGE_KEY, next);
}

export function useTheme(): UseTheme {
  return {
    theme,
    isDark: computed(() => theme.value === 'catalogDark'),
    setTheme: applyTheme,
    toggleTheme: () => applyTheme(theme.value === 'catalogDark' ? 'catalogLight' : 'catalogDark'),
  };
}
