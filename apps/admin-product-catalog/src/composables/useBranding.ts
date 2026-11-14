import { computed, ref, type ComputedRef, type Ref } from 'vue';

import { getThemeConfig } from '@/lib/trustApi';
import {
  DEFAULT_THEME,
  applyTheme,
  clearTheme,
  normalizeTheme,
  readCachedTheme,
  writeCachedTheme,
  type ThemeConfig,
} from '@/lib/themeEngine';

/**
 * The published theme & branding (the same config the storefront renders), as shared reactive
 * state. `bootstrapBranding()` runs once before mount: it paints the cached config synchronously
 * (no flash of the default theme on repeat visits), then reconciles with the server.
 *
 * `null` = nothing published, so the built-in stylesheet is left untouched.
 */
const published: Ref<ThemeConfig | null> = ref(null);

function setTitle(cfg: ThemeConfig | null): void {
  document.title = `${(cfg ?? DEFAULT_THEME).brand.name} Admin`;
}

/** Apply a config now (used by the editor for live preview, and after save/reset). */
export function paintTheme(cfg: ThemeConfig | null): void {
  if (cfg) applyTheme(cfg, 'admin');
  else clearTheme();
  setTitle(cfg);
}

/** Record what the server now has published, and repaint with it. */
export function setPublished(raw: unknown | null): void {
  published.value = raw ? normalizeTheme(raw) : null;
  writeCachedTheme(published.value);
  paintTheme(published.value);
}

export async function bootstrapBranding(): Promise<void> {
  const cached = readCachedTheme();
  if (cached) {
    published.value = cached;
    paintTheme(cached);
  }
  try {
    const res = await getThemeConfig();
    setPublished(res.config);
  } catch {
    // Gateway/product-service down: keep whatever we painted from cache (or the built-in theme).
  }
}

export interface UseBranding {
  readonly published: Ref<ThemeConfig | null>;
  readonly name: ComputedRef<string>;
  readonly logoUrl: ComputedRef<string>;
}

export function useBranding(): UseBranding {
  return {
    published,
    name: computed(() => (published.value ?? DEFAULT_THEME).brand.name),
    logoUrl: computed(() => published.value?.brand.logoUrl ?? ''),
  };
}
