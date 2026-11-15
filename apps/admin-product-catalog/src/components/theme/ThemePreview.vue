<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { DEFAULT_THEME, STYLE_FX, deriveTokens, type ShadowStyle, type ThemeConfig } from '@/lib/themeEngine';

/**
 * Live mock of the storefront and the admin console, driven by scoped `--p-*` variables so it can
 * show either colour mode (or both), either surface, at desktop or phone width, before/after the
 * published theme, and through a colour-vision-deficiency filter - all without touching the real
 * page. Purely presentational: it never mutates the config.
 */
const props = defineProps<{
  /** The draft being edited. */
  config: ThemeConfig;
  /** What is live right now (null = built-in theme), for the before/after view. */
  published: ThemeConfig | null;
  /** The editor's active colour mode; "Follow editor" shows this one. */
  mode: 'light' | 'dark';
  dirty: boolean;
}>();

type Surface = 'storefront' | 'admin';
type Device = 'desktop' | 'mobile';
type Modes = 'follow' | 'both';
type Vision = 'none' | 'protanopia' | 'deuteranopia' | 'tritanopia' | 'achromatopsia';

const VISIONS: readonly { value: Vision; label: string }[] = [
  { value: 'none', label: 'Typical vision' },
  { value: 'protanopia', label: 'Protanopia (no red)' },
  { value: 'deuteranopia', label: 'Deuteranopia (no green)' },
  { value: 'tritanopia', label: 'Tritanopia (no blue)' },
  { value: 'achromatopsia', label: 'Achromatopsia (greyscale)' },
];

/** Machado et al. (2009) full-severity simulation matrices, as SVG feColorMatrix rows. */
const CVD_MATRIX: Record<Exclude<Vision, 'none'>, string> = {
  protanopia: '0.152 1.053 -0.205 0 0  0.115 0.786 0.099 0 0  -0.004 -0.048 1.052 0 0  0 0 0 1 0',
  deuteranopia: '0.367 0.861 -0.228 0 0  0.280 0.673 0.047 0 0  -0.012 0.043 0.969 0 0  0 0 0 1 0',
  tritanopia: '1.256 -0.077 -0.179 0 0  -0.078 0.931 0.148 0 0  0.005 0.691 0.304 0 0  0 0 0 1 0',
  achromatopsia: '0.299 0.587 0.114 0 0  0.299 0.587 0.114 0 0  0.299 0.587 0.114 0 0  0 0 0 1 0',
};

const PREFS_KEY = 'cw_theme_preview_prefs_v1';

interface Prefs {
  surface: Surface;
  device: Device;
  modes: Modes;
  compare: boolean;
  vision: Vision;
}

function readPrefs(): Prefs {
  const fallback: Prefs = { surface: 'storefront', device: 'desktop', modes: 'follow', compare: false, vision: 'none' };
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    return raw ? { ...fallback, ...(JSON.parse(raw) as Partial<Prefs>) } : fallback;
  } catch {
    return fallback;
  }
}

const prefs = ref<Prefs>(readPrefs());
watch(
  prefs,
  (p) => {
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify(p));
    } catch {
      // Per-viewer convenience only.
    }
  },
  { deep: true },
);

const modesToShow = computed<('light' | 'dark')[]>(() =>
  prefs.value.modes === 'both' ? ['light', 'dark'] : [props.mode],
);

interface Frame {
  key: string;
  label: string;
  config: ThemeConfig;
  mode: 'light' | 'dark';
}

const frames = computed<Frame[]>(() => {
  const out: Frame[] = [];
  const sources: { tag: string; config: ThemeConfig }[] = prefs.value.compare
    ? [
        { tag: 'Published', config: props.published ?? DEFAULT_THEME },
        { tag: 'Draft', config: props.config },
      ]
    : [{ tag: '', config: props.config }];
  for (const s of sources) {
    for (const m of modesToShow.value) {
      const parts = [s.tag, m === 'light' ? 'Light' : 'Dark'].filter(Boolean);
      out.push({ key: `${s.tag}-${m}`, label: parts.join(' · '), config: s.config, mode: m });
    }
  }
  return out;
});

/** The scoped variables one frame renders with. Mirrors themeEngine's derivations. */
function frameStyle(cfg: ThemeConfig, mode: 'light' | 'dark'): Record<string, string> {
  const t = deriveTokens(cfg[mode], mode);
  const radius = cfg.border.radius;
  const r = radius === 'sharp' ? '4px' : radius === 'round' ? '22px' : '12px';
  const bw = { thin: '1px', standard: '2px', bold: '3px' }[cfg.border.width];
  const shadows: Record<ShadowStyle, string> = {
    none: 'none',
    soft: '0 2px 8px rgba(0,0,0,.16)',
    crisp: `2px 2px 0 ${t.ink}`,
    retro: `4px 4px 0 ${t.ink}`,
    lifted: '0 10px 26px -8px rgba(0,0,0,.4)',
  };
  const scale = { small: '0.94', default: '1', large: '1.09' }[cfg.options.textScale];
  return {
    '--p-bg': t.background,
    '--p-surface': t.surface,
    '--p-surface-2': t.surfaceVariant,
    '--p-text': t.onSurface,
    '--p-muted': t.muted,
    '--p-outline': t.outline,
    '--p-primary': t.primary,
    '--p-on-primary': t.onPrimary,
    '--p-accent': t.accent,
    '--p-on-accent': t.onAccent,
    '--p-deal': t.deal,
    '--p-on-error': t.onError,
    '--p-ink': t.ink,
    '--p-radius': r,
    '--p-bw': bw,
    '--p-shadow': shadows[cfg.shadow.style],
    '--p-scale': scale,
    '--p-hi': mode === 'dark' ? 'rgba(255,255,255,.07)' : 'rgba(255,255,255,.85)',
    '--p-lo': mode === 'dark' ? 'rgba(0,0,0,.6)' : 'rgba(30,40,70,.18)',
    '--p-display': `'${cfg.fonts.display}', system-ui, sans-serif`,
    '--p-body': `'${cfg.fonts.body}', system-ui, sans-serif`,
  };
}

function fxClasses(cfg: ThemeConfig): string {
  const f = STYLE_FX[cfg.style.id];
  return `pb-${f.backdrop} ph-${f.heading} pbtn-${f.button} pv-${f.surface}`;
}

const visionFilter = computed(() => (prefs.value.vision === 'none' ? undefined : `url(#cw-cvd-${prefs.value.vision})`));

const PRODUCTS = [
  { name: 'Trail water bottle', price: '$18.00', sale: '-25%', stars: '★★★★☆', count: 128 },
  { name: 'Linen tote bag', price: '$32.00', sale: '', stars: '★★★★★', count: 64 },
  { name: 'Desk lamp, brass', price: '$74.00', sale: '-10%', stars: '★★★★☆', count: 212 },
] as const;
</script>

<template>
  <div class="preview-root" data-test="preview">
    <!-- CVD filters, referenced by url(#id). Hidden, zero-size. -->
    <svg width="0" height="0" aria-hidden="true" focusable="false" style="position: absolute">
      <filter v-for="(m, k) in CVD_MATRIX" :id="`cw-cvd-${k}`" :key="k" color-interpolation-filters="sRGB">
        <feColorMatrix type="matrix" :values="m" />
      </filter>
    </svg>

    <div class="preview-toolbar" role="toolbar" aria-label="Preview options">
      <div role="tablist" class="tabs tabs-box tabs-xs">
        <button v-for="s in ['storefront', 'admin'] as const" :key="s" role="tab" type="button" class="tab"
          :class="{ 'tab-active': prefs.surface === s }" :aria-selected="prefs.surface === s" @click="prefs.surface = s">
          {{ s === 'storefront' ? 'Storefront' : 'Admin console' }}
        </button>
      </div>
      <div role="tablist" class="tabs tabs-box tabs-xs">
        <button v-for="d in ['desktop', 'mobile'] as const" :key="d" role="tab" type="button" class="tab"
          :class="{ 'tab-active': prefs.device === d }" :aria-selected="prefs.device === d" :title="d === 'desktop' ? 'Desktop width' : 'Phone width'" @click="prefs.device = d">
          {{ d === 'desktop' ? '🖥' : '📱' }}
        </button>
      </div>
      <div role="tablist" class="tabs tabs-box tabs-xs">
        <button role="tab" type="button" class="tab" :class="{ 'tab-active': prefs.modes === 'follow' }" :aria-selected="prefs.modes === 'follow'" title="Show the mode you are editing" @click="prefs.modes = 'follow'">
          {{ mode === 'light' ? 'Light' : 'Dark' }}
        </button>
        <button role="tab" type="button" class="tab" :class="{ 'tab-active': prefs.modes === 'both' }" :aria-selected="prefs.modes === 'both'" title="Light and dark side by side" @click="prefs.modes = 'both'">
          Both
        </button>
      </div>
      <label class="flex cursor-pointer items-center gap-1 text-xs" :title="published ? 'Compare with what is published' : 'Compare with the built-in theme'">
        <input v-model="prefs.compare" type="checkbox" class="toggle toggle-xs" />
        Before / after
      </label>
      <select v-model="prefs.vision" class="select select-bordered select-xs" aria-label="Simulate colour vision">
        <option v-for="v in VISIONS" :key="v.value" :value="v.value">{{ v.label }}</option>
      </select>
    </div>

    <div class="frames" :class="[`device-${prefs.device}`, { 'frames-many': frames.length > 1 }]" :style="{ filter: visionFilter }">
      <figure v-for="f in frames" :key="f.key" class="frame">
        <figcaption v-if="f.label" class="frame-label">
          {{ f.label }}
          <span v-if="f.label.startsWith('Draft') && dirty" class="badge badge-warning badge-xs">unpublished</span>
        </figcaption>
        <div class="device" :class="`device-${prefs.device}`">
          <!-- ===== Storefront ===== -->
          <div v-if="prefs.surface === 'storefront'" class="mock" :class="fxClasses(f.config)" :style="frameStyle(f.config, f.mode)">
            <div class="mock-header">
              <img v-if="f.config.brand.logoUrl" :src="f.config.brand.logoUrl" alt="" class="mock-logo" />
              <span v-else class="mock-dot" aria-hidden="true" />
              <strong class="mock-brand">{{ f.config.brand.name || 'Store name' }}</strong>
              <span class="mock-search" aria-hidden="true">Search…</span>
              <span class="mock-cart" aria-hidden="true">🛒 <b>2</b></span>
            </div>
            <nav class="mock-nav" aria-hidden="true">
              <span class="mock-nav-item mock-nav-active">All</span>
              <span class="mock-nav-item">Deals</span>
              <span class="mock-nav-item">Home</span>
              <span class="mock-nav-item">Tech</span>
            </nav>
            <div class="mock-body">
              <p class="mock-tagline">{{ f.config.brand.tagline || 'Your tagline here' }}</p>
              <h3 class="mock-title">Everyday essentials</h3>
              <p class="mock-copy">Honest deals on the things you actually use, delivered fast.</p>
              <div class="mock-row">
                <button type="button" class="mock-btn mock-btn-primary" tabindex="-1">Shop now</button>
                <button type="button" class="mock-btn mock-btn-accent" tabindex="-1">Deals</button>
                <a class="mock-link" tabindex="-1">Learn more →</a>
              </div>
              <div class="mock-grid">
                <div v-for="p in PRODUCTS" :key="p.name" class="mock-card mock-surface">
                  <div class="mock-img" aria-hidden="true" />
                  <div class="min-w-0">
                    <div class="mock-name">{{ p.name }}</div>
                    <div class="mock-muted">{{ p.stars }} ({{ p.count }})</div>
                    <div><span class="mock-price">{{ p.price }}</span> <span v-if="p.sale" class="mock-sale">{{ p.sale }}</span></div>
                  </div>
                </div>
              </div>
              <div class="mock-input-row">
                <span class="mock-input">you@example.com</span>
                <button type="button" class="mock-btn mock-btn-primary" tabindex="-1">Subscribe</button>
              </div>
              <p class="mock-error">⚠ Something went wrong - this is what an error looks like.</p>
            </div>
            <div class="mock-footer">© {{ new Date().getFullYear() }} {{ f.config.brand.name || 'Store name' }}</div>
          </div>

          <!-- ===== Admin console ===== -->
          <div v-else class="mock mock-admin" :class="fxClasses(f.config)" :style="frameStyle(f.config, f.mode)">
            <aside class="mock-side">
              <div class="mock-side-brand">
                <img v-if="f.config.brand.logoUrl" :src="f.config.brand.logoUrl" alt="" class="mock-logo" />
                <span v-else class="mock-dot" aria-hidden="true" />
                <strong>{{ f.config.brand.name || 'Store' }}</strong> <span class="mock-admin-tag">Admin</span>
              </div>
              <span class="mock-side-item mock-side-active">Dashboard</span>
              <span class="mock-side-item">Products</span>
              <span class="mock-side-item">Orders</span>
              <span class="mock-side-item">Customers</span>
              <span class="mock-side-item">Theme</span>
            </aside>
            <div class="mock-main">
              <h3 class="mock-title">Dashboard</h3>
              <div class="mock-stats">
                <div class="mock-stat mock-surface"><span class="mock-muted">Orders today</span><b>128</b></div>
                <div class="mock-stat mock-surface"><span class="mock-muted">Revenue</span><b>$4,210</b></div>
                <div class="mock-stat mock-surface"><span class="mock-muted">Low stock</span><b class="mock-warn">7</b></div>
              </div>
              <div class="mock-table mock-surface">
                <div class="mock-tr mock-th"><span>Order</span><span>Customer</span><span>Status</span></div>
                <div class="mock-tr"><span>#1042</span><span>A. Rivera</span><span class="mock-pill mock-pill-ok">Paid</span></div>
                <div class="mock-tr"><span>#1041</span><span>J. Okafor</span><span class="mock-pill">Pending</span></div>
                <div class="mock-tr"><span>#1040</span><span>M. Chen</span><span class="mock-pill mock-pill-bad">Refunded</span></div>
              </div>
              <div class="mock-row">
                <button type="button" class="mock-btn mock-btn-primary" tabindex="-1">Save</button>
                <button type="button" class="mock-btn mock-btn-ghost" tabindex="-1">Cancel</button>
              </div>
            </div>
          </div>
        </div>
      </figure>
    </div>
  </div>
</template>

<style scoped>
.preview-root {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}
.preview-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem 0.6rem;
}
.frames {
  display: grid;
  gap: 0.75rem;
}
.frames-many.device-desktop {
  grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
}
.frames-many.device-mobile {
  grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
}
.frame {
  margin: 0;
  min-width: 0;
}
.frame-label {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  margin-bottom: 0.25rem;
  font-size: 0.7rem;
  font-weight: 600;
  opacity: 0.75;
}
.device-mobile .device {
  max-width: 19rem;
  margin: 0 auto;
  padding: 0.55rem;
  border-radius: 1.6rem;
  background: var(--color-base-content);
}
.device-mobile .device .mock {
  border-radius: 1.1rem;
}
.device-mobile .mock-grid {
  grid-template-columns: 1fr;
}
.device-mobile .mock-search,
.device-mobile .mock-nav-item:nth-child(n + 4) {
  display: none;
}
.device-mobile .mock-admin {
  grid-template-columns: 1fr;
}
.device-mobile .mock-side {
  flex-direction: row;
  flex-wrap: wrap;
}

/* ===== Mock frame: everything reads the --p-* variables set inline ===== */
.mock {
  --fs: calc(0.78rem * var(--p-scale));
  background: var(--p-bg);
  color: var(--p-text);
  font-family: var(--p-body);
  border: 1px solid var(--p-outline);
  border-radius: var(--p-radius);
  overflow: hidden;
  font-size: var(--fs);
  line-height: 1.4;
}
.mock-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.55rem 0.75rem;
  background: var(--p-primary);
  color: var(--p-on-primary);
  font-family: var(--p-display);
}
.mock-brand {
  font-size: 1.05em;
}
.mock-search {
  flex: 1;
  margin-left: 0.5rem;
  padding: 0.15rem 0.6rem;
  border-radius: 9999px;
  background: color-mix(in srgb, var(--p-on-primary) 18%, transparent);
  color: var(--p-on-primary);
  opacity: 0.8;
  font-size: 0.85em;
}
.mock-cart {
  margin-left: auto;
  font-size: 0.9em;
}
.mock-logo {
  width: 1.5rem;
  height: 1.5rem;
  object-fit: contain;
}
.mock-dot {
  width: 1.1rem;
  height: 1.1rem;
  border-radius: 9999px;
  background: var(--p-accent);
  border: 2px solid var(--p-ink);
  flex: none;
}
.mock-nav {
  display: flex;
  gap: 0.35rem;
  padding: 0.4rem 0.75rem;
  background: var(--p-surface);
  border-bottom: var(--p-bw) solid var(--p-outline);
}
.mock-nav-item {
  padding: 0.1rem 0.55rem;
  border-radius: 9999px;
  font-size: 0.85em;
}
.mock-nav-active {
  background: var(--p-accent);
  color: var(--p-on-accent);
  border: var(--p-bw) solid var(--p-ink);
}
.mock-body {
  padding: 0.85rem 0.75rem;
}
.mock-tagline {
  color: var(--p-muted);
  font-size: 0.8em;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.mock-title {
  font-family: var(--p-display);
  font-size: 1.5em;
  font-weight: 700;
  margin: 0.1rem 0 0.2rem;
}
.mock-copy {
  color: var(--p-muted);
  margin-bottom: 0.65rem;
}
.mock-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.45rem;
  margin-bottom: 0.75rem;
}
.mock-btn {
  border-radius: var(--p-radius);
  padding: 0.3rem 0.75rem;
  font-weight: 600;
  font-size: 0.95em;
  border: var(--p-bw) solid var(--p-ink);
  box-shadow: var(--p-shadow);
}
.mock-btn-primary {
  background: var(--p-primary);
  color: var(--p-on-primary);
}
.mock-btn-accent {
  background: var(--p-accent);
  color: var(--p-on-accent);
}
.mock-btn-ghost {
  background: transparent;
  color: var(--p-text);
  border-color: var(--p-outline);
  box-shadow: none;
}
.mock-link {
  color: var(--p-primary);
  text-decoration: underline;
  font-weight: 600;
  font-size: 0.95em;
}
.mock-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
  gap: 0.55rem;
  margin-bottom: 0.75rem;
}
.mock-card {
  display: flex;
  gap: 0.55rem;
  padding: 0.55rem;
}
.mock-surface {
  background: var(--p-surface);
  border: var(--p-bw) solid var(--p-outline);
  border-radius: var(--p-radius);
  box-shadow: var(--p-shadow);
}
.mock-img {
  width: 2.9rem;
  height: 2.9rem;
  flex: none;
  border-radius: calc(var(--p-radius) * 0.6);
  background: linear-gradient(135deg, var(--p-primary), var(--p-accent));
}
.mock-name {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mock-muted {
  color: var(--p-muted);
  font-size: 0.85em;
}
.mock-price {
  font-weight: 700;
}
.mock-sale {
  background: var(--p-deal);
  color: var(--p-on-error);
  border-radius: 9999px;
  padding: 0 0.4rem;
  font-size: 0.8em;
  font-weight: 700;
}
.mock-input-row {
  display: flex;
  gap: 0.45rem;
  margin-bottom: 0.6rem;
}
.mock-input {
  flex: 1;
  padding: 0.3rem 0.6rem;
  border: var(--p-bw) solid var(--p-outline);
  border-radius: var(--p-radius);
  background: var(--p-surface);
  color: var(--p-muted);
}
.mock-error {
  color: var(--p-deal);
  font-size: 0.85em;
  margin: 0;
}
.mock-footer {
  padding: 0.5rem 0.75rem;
  background: var(--p-primary);
  color: var(--p-on-primary);
  font-size: 0.8em;
  opacity: 0.95;
}

/* Admin console mock */
.mock-admin {
  display: grid;
  grid-template-columns: 7rem 1fr;
  min-height: 15rem;
}
.mock-side {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  padding: 0.6rem 0.5rem;
  background: var(--p-primary);
  color: var(--p-on-primary);
}
.mock-side-brand {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  margin-bottom: 0.5rem;
  font-family: var(--p-display);
  font-size: 0.95em;
}
.mock-admin-tag {
  font-size: 0.65em;
  padding: 0 0.35rem;
  border-radius: 9999px;
  background: var(--p-accent);
  color: var(--p-on-accent);
  border: 1px solid var(--p-ink);
}
.mock-side-item {
  padding: 0.2rem 0.5rem;
  border-radius: 9999px;
  font-size: 0.9em;
  opacity: 0.85;
}
.mock-side-active {
  background: var(--p-accent);
  color: var(--p-on-accent);
  border: var(--p-bw) solid var(--p-ink);
  opacity: 1;
}
.mock-main {
  padding: 0.75rem;
}
.mock-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(5.5rem, 1fr));
  gap: 0.5rem;
  margin-bottom: 0.6rem;
}
.mock-stat {
  display: flex;
  flex-direction: column;
  padding: 0.5rem;
}
.mock-stat b {
  font-size: 1.3em;
  font-family: var(--p-display);
}
.mock-warn {
  color: var(--p-deal);
}
.mock-table {
  margin-bottom: 0.6rem;
  overflow: hidden;
}
.mock-tr {
  display: grid;
  grid-template-columns: 1fr 1.4fr 1fr;
  gap: 0.4rem;
  padding: 0.3rem 0.55rem;
  border-top: 1px solid var(--p-outline);
  align-items: center;
}
.mock-th {
  border-top: 0;
  background: var(--p-surface-2);
  font-weight: 700;
  font-size: 0.85em;
}
.mock-pill {
  justify-self: start;
  padding: 0 0.45rem;
  border-radius: 9999px;
  font-size: 0.8em;
  font-weight: 600;
  background: var(--p-surface-2);
}
.mock-pill-ok {
  background: var(--p-primary);
  color: var(--p-on-primary);
}
.mock-pill-bad {
  background: var(--p-deal);
  color: var(--p-on-error);
}

/* Design-style effects, same recipes as themeEngine's STYLE_FX rules. */
.pv-glass .mock-surface {
  background: color-mix(in srgb, var(--p-surface) 58%, transparent);
  backdrop-filter: blur(12px) saturate(150%);
  box-shadow: 0 8px 24px -8px var(--p-lo);
}
.pv-liquid .mock-surface {
  background: linear-gradient(135deg, color-mix(in srgb, var(--p-surface) 62%, transparent), color-mix(in srgb, var(--p-surface) 38%, transparent));
  backdrop-filter: blur(18px) saturate(190%);
  border-color: color-mix(in srgb, white 45%, transparent);
  box-shadow: inset 0 1px 0 color-mix(in srgb, white 70%, transparent), 0 14px 30px -12px var(--p-lo);
}
.pv-neu .mock-surface {
  background: var(--p-bg);
  border-color: transparent;
  box-shadow: 8px 8px 16px var(--p-lo), -8px -8px 16px var(--p-hi);
}
.pv-clay .mock-surface {
  border-color: transparent;
  box-shadow: inset -5px -5px 10px color-mix(in srgb, var(--p-text) 10%, transparent), inset 5px 5px 10px var(--p-hi), 8px 12px 22px -6px var(--p-lo);
}
.pv-skeuo .mock-surface {
  background: linear-gradient(180deg, color-mix(in srgb, var(--p-surface) 86%, white), var(--p-surface) 55%, color-mix(in srgb, var(--p-surface) 90%, black));
  box-shadow: inset 0 1px 0 var(--p-hi), inset 0 -2px 4px var(--p-lo), 0 10px 18px -8px var(--p-lo);
}
.pb-aurora {
  background-image: radial-gradient(22rem 14rem at 6% -6%, color-mix(in srgb, var(--p-primary) 34%, transparent), transparent 62%), radial-gradient(18rem 12rem at 96% 4%, color-mix(in srgb, var(--p-accent) 30%, transparent), transparent 62%);
}
.pb-grid {
  background-image: linear-gradient(color-mix(in srgb, var(--p-text) 7%, transparent) 1px, transparent 1px), linear-gradient(90deg, color-mix(in srgb, var(--p-text) 7%, transparent) 1px, transparent 1px);
  background-size: 24px 24px;
}
.pb-dots {
  background-image: radial-gradient(color-mix(in srgb, var(--p-text) 15%, transparent) 1.3px, transparent 1.4px);
  background-size: 16px 16px;
}
.pb-scanlines {
  background-image: repeating-linear-gradient(0deg, color-mix(in srgb, var(--p-text) 6%, transparent) 0 1px, transparent 1px 3px);
}
.pb-spatial {
  background-image: radial-gradient(26rem 14rem at 50% -12%, color-mix(in srgb, var(--p-primary) 24%, transparent), transparent 70%);
}
.pb-paper {
  background-image: repeating-linear-gradient(45deg, color-mix(in srgb, var(--p-text) 3%, transparent) 0 1px, transparent 1px 5px);
}
.ph-upper .mock-title {
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.ph-tight .mock-title {
  letter-spacing: -0.02em;
  font-weight: 800;
}
.pbtn-flat .mock-btn {
  box-shadow: none;
}
.pbtn-glow .mock-btn-accent {
  box-shadow: 0 0 14px color-mix(in srgb, var(--p-accent) 65%, transparent), 0 0 2px var(--p-accent);
}
</style>
