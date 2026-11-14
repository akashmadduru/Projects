/**
 * Published theme & branding (set by an admin in the console, served by product-service).
 *
 * `bootstrapBranding()` runs before first render: it paints the last-seen config from localStorage
 * synchronously (no flash of the built-in theme on repeat visits), then reconciles with the server.
 * `config === null` means nothing is published and the built-in stylesheet is left untouched.
 */

import { create } from 'zustand'
import { apiFetch } from '../lib/http'
import {
  DEFAULT_THEME,
  applyTheme,
  clearTheme,
  normalizeTheme,
  readCachedTheme,
  writeCachedTheme,
  type ThemeConfig,
} from '../lib/themeEngine'

interface BrandState {
  config: ThemeConfig | null
  set: (raw: unknown | null) => void
}

const BUILT_IN_TITLE = /^Cartwise\b/

function paint(cfg: ThemeConfig | null): void {
  if (cfg) applyTheme(cfg, 'storefront')
  else clearTheme()
  const b = (cfg ?? DEFAULT_THEME).brand
  // Page-level titles (useHead) set their own; only rebrand the untouched default one.
  if (BUILT_IN_TITLE.test(document.title) || document.title === b.name) {
    document.title = b.tagline ? `${b.name} — ${b.tagline}` : b.name
  }
}

export const useBrandStore = create<BrandState>((set) => ({
  config: null,
  set: (raw) => {
    const cfg = raw ? normalizeTheme(raw) : null
    writeCachedTheme(cfg)
    paint(cfg)
    set({ config: cfg })
  },
}))

export function useBrand() {
  const cfg = useBrandStore((s) => s.config) ?? DEFAULT_THEME
  return { name: cfg.brand.name, tagline: cfg.brand.tagline, logoUrl: cfg.brand.logoUrl }
}

export function bootstrapBranding(): void {
  const cached = readCachedTheme()
  if (cached) {
    paint(cached)
    useBrandStore.setState({ config: cached })
  }
  apiFetch<{ config: unknown | null }>('/api/products/theme')
    .then((res) => useBrandStore.getState().set(res.config))
    .catch(() => undefined) // keep the cached / built-in theme if the API is unreachable
}
