/**
 * Lightweight i18n: English strings are the keys, so `t('Sign in')` returns the string itself for
 * English and the Hindi translation (lazy-loaded dictionary) for `hi`. Missing keys fall back to English.
 */

import { create } from 'zustand'

export type Lang = 'en' | 'hi'
export const LANGS: { code: Lang; label: string }[] = [
  { code: 'en', label: 'English' },
  { code: 'hi', label: 'हिन्दी' },
]

const KEY = 'lang'
type Dict = Record<string, string>

function initialLang(): Lang {
  try {
    const stored = localStorage.getItem(KEY)
    if (stored === 'en' || stored === 'hi') return stored
  } catch {
    // Storage unavailable; use the browser language.
  }
  return typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('hi') ? 'hi' : 'en'
}

let currentLang: Lang = initialLang()

/** Current language for non-React callers (the HTTP client's Accept-Language header). */
export function getLang(): Lang {
  return currentLang
}

interface LocaleState {
  lang: Lang
  dict: Dict
  setLang: (lang: Lang) => void
}

async function loadDict(lang: Lang): Promise<Dict> {
  if (lang === 'en') return {}
  return (await import('../locales/hi')).hi
}

export const useLocale = create<LocaleState>()((set) => ({
  lang: currentLang,
  dict: {},
  setLang: (lang) => {
    currentLang = lang
    try {
      localStorage.setItem(KEY, lang)
    } catch {
      // Preference just will not persist.
    }
    document.documentElement.lang = lang
    set({ lang })
    void loadDict(lang).then((dict) => {
      if (currentLang === lang) set({ dict })
    })
  },
}))

// Apply the persisted language on startup.
if (typeof document !== 'undefined') {
  document.documentElement.lang = currentLang
  if (currentLang !== 'en') void loadDict(currentLang).then((dict) => useLocale.setState({ dict }))
}

/** Hook returning a translate function; re-renders when the language or dictionary changes. */
export function useT(): (text: string) => string {
  const dict = useLocale((s) => s.dict)
  return (text) => dict[text] ?? text
}
