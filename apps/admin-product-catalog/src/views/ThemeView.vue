<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { ApiError } from '@/lib/problem';
import { paintTheme, setPublished, useBranding } from '@/composables/useBranding';
import { errorInfo } from '@/lib/adminApi';
import {
  DEFAULT_THEME,
  FONT_OPTIONS,
  FONT_PAIRS,
  BORDER_WIDTHS,
  RADIUS_KINDS,
  SHADOW_STYLES,
  STYLE_FX,
  MOTION_LEVELS,
  TEXT_SCALES,
  autoFixPalette,
  deriveDarkFromLight,
  generatePalette,
  randomPalette,
  type MotionLevel,
  type TextScale,
  type BorderWidth,
  type StyleId,
  type RadiusKind,
  type ShadowStyle,
  PALETTE_KEYS,
  contrastChecks,
  customCssProblem,
  deriveTokens,
  isHex,
  normalizeTheme,
  type PaletteKey,
  type ThemeConfig,
  type ThemePalette,
} from '@/lib/themeEngine';
import { THEME_PRESETS } from '@/lib/themePresets';
import { STYLE_KITS } from '@/lib/themeStyles';
import { getThemeConfig, resetThemeConfig, saveThemeConfig } from '@/lib/trustApi';

/**
 * Theme & branding editor. Edits are painted LIVE on this console (so the admin sees exactly what
 * it will look like) but only reach the storefront - and everyone else's console - on "Publish".
 * Leaving without publishing reverts the live preview.
 */
type ErrorInfo = { message: string; correlationId: string | null };
type Mode = 'light' | 'dark';

const PALETTE_LABELS: Record<PaletteKey, { label: string; hint: string }> = {
  primary: { label: 'Primary', hint: 'Buttons, links, header' },
  accent: { label: 'Accent', hint: 'Highlights, badges, secondary actions' },
  background: { label: 'Background', hint: 'Page canvas' },
  surface: { label: 'Surface', hint: 'Cards, menus, inputs' },
  text: { label: 'Text', hint: 'Body copy and lines' },
  danger: { label: 'Danger / deals', hint: 'Errors, discounts, sale tags' },
};


/** structuredClone throws DataCloneError on Vue reactive proxies; the config is plain JSON, so round-trip it. */
function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T;
}

const { published } = useBranding();

const draft = ref<ThemeConfig>(clone(DEFAULT_THEME));
const baseline = ref<string>(JSON.stringify(DEFAULT_THEME));
const mode = ref<Mode>('light');
const loading = ref(false);
const saving = ref(false);
const error = ref<ErrorInfo | null>(null);
const notice = ref('');
const publishedAt = ref<string | null>(null);
const brandColour = ref('#176d65');
const jsonText = ref('');
const jsonError = ref('');

type Section = 'options' | 'style' | 'colours' | 'fonts' | 'borders' | 'shadows';

const applyKitLook = ref(true);

/** Per-section "changed since last publish" flags, so each concern can be reviewed on its own. */
const sectionDirty = computed<Record<Section, boolean>>(() => {
  const base = JSON.parse(baseline.value) as ThemeConfig;
  const d = draft.value;
  const diff = (a: unknown, b: unknown) => JSON.stringify(a) !== JSON.stringify(b);
  return {
    colours: diff([d.light, d.dark], [base.light, base.dark]),
    fonts: diff(d.fonts, base.fonts),
    borders: diff(d.border, base.border),
    shadows: diff(d.shadow, base.shadow),
    style: diff(d.style, base.style),
    options: diff(d.options, base.options),
  };
});

const dirty = computed(() => JSON.stringify(draft.value) !== baseline.value);
const cssProblem = computed(() => customCssProblem(draft.value.customCss));
const checks = computed(() => contrastChecks(draft.value[mode.value]));
const failing = computed(() => checks.value.filter((c) => !c.ok));
const tokens = computed(() => deriveTokens(draft.value[mode.value], mode.value));
const canSave = computed(
  () => dirty.value && !cssProblem.value && draft.value.brand.name.trim().length > 0 && !saving.value,
);

/** Scoped CSS variables for the mock storefront card, so it previews the chosen mode regardless of the console's own mode. */
const previewFx = computed(() => STYLE_FX[draft.value.style.id]);

const previewStyle = computed(() => {
  const t = tokens.value;
  const radius = draft.value.border.radius;
  const r = radius === 'sharp' ? '4px' : radius === 'round' ? '22px' : '12px';
  const bw = { thin: '1px', standard: '2px', bold: '3px' }[draft.value.border.width];
  const shadows: Record<ShadowStyle, string> = {
    none: 'none',
    soft: '0 2px 8px rgba(0,0,0,.16)',
    crisp: '2px 2px 0 ' + t.ink,
    retro: '4px 4px 0 ' + t.ink,
    lifted: '0 10px 26px -8px rgba(0,0,0,.4)',
  };
  return {
    '--p-bg': t.background,
    '--p-surface': t.surface,
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
    '--p-shadow': shadows[draft.value.shadow.style],
    '--p-hi': mode.value === 'dark' ? 'rgba(255,255,255,.07)' : 'rgba(255,255,255,.85)',
    '--p-lo': mode.value === 'dark' ? 'rgba(0,0,0,.6)' : 'rgba(30,40,70,.18)',
    '--p-display': `'${draft.value.fonts.display}', system-ui, sans-serif`,
    '--p-body': `'${draft.value.fonts.body}', system-ui, sans-serif`,
  } as Record<string, string>;
});

function setBaseline(cfg: ThemeConfig): void {
  draft.value = clone(cfg);
  baseline.value = JSON.stringify(cfg);
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const res = await getThemeConfig();
    publishedAt.value = res.updatedAt;
    setBaseline(res.config ? normalizeTheme(res.config) : DEFAULT_THEME);
    resetHistory();
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    loading.value = false;
  }
}

function applyPreset(id: string): void {
  const p = THEME_PRESETS.find((x) => x.id === id);
  if (!p) return;
  // Keep the merchant's own brand text; a preset swaps the look, not the identity.
  draft.value = clone({ ...p.config, brand: draft.value.brand });
}

function setColor(key: PaletteKey, value: string): void {
  if (!isHex(value)) return;
  const next: ThemePalette = { ...draft.value[mode.value], [key]: value.toLowerCase() };
  draft.value = { ...draft.value, preset: 'custom', [mode.value]: next };
}

/** Starts the dark palette from the light one's hue family is not derivable; offer a copy instead. */
function copyPaletteToOther(): void {
  const from = mode.value;
  const to: Mode = from === 'light' ? 'dark' : 'light';
  draft.value = { ...draft.value, preset: 'custom', [to]: { ...draft.value[from] } };
  notice.value = `Copied the ${from} palette to ${to}. Adjust background/surface/text so it still reads well.`;
}

function applyFontPair(id: string): void {
  const p = FONT_PAIRS.find((x) => x.id === id);
  if (p) draft.value = { ...draft.value, preset: 'custom', fonts: { display: p.display, body: p.body } };
}

/**
 * Choose a design language. Its runtime effects always switch on; the starter kit (palette, fonts,
 * corners, outline, shadow) is applied too unless the admin wants to keep their own look.
 */
function applyStyle(id: StyleId): void {
  const kit = STYLE_KITS.find((k) => k.id === id);
  if (!kit) return;
  const next: ThemeConfig = { ...draft.value, style: { id } };
  if (applyKitLook.value) {
    next.preset = 'custom';
    next.light = { ...kit.light };
    next.dark = { ...kit.dark };
    next.fonts = { ...kit.fonts };
    next.border = { ...kit.border };
    next.shadow = { style: kit.shadow };
  }
  draft.value = next;
}

// ---- colour tools --------------------------------------------------------------------------

function patchPalette(m: Mode, palette: ThemePalette): void {
  draft.value = { ...draft.value, preset: 'custom', [m]: palette };
}

function generateFromBrand(both: boolean): void {
  if (!isHex(brandColour.value)) return;
  if (both) {
    draft.value = {
      ...draft.value,
      preset: 'custom',
      light: generatePalette(brandColour.value, 'light'),
      dark: generatePalette(brandColour.value, 'dark'),
    };
  } else {
    patchPalette(mode.value, generatePalette(brandColour.value, mode.value));
  }
  notice.value = 'Palette generated from your brand colour. Tweak any swatch below.';
}

function deriveDark(): void {
  patchPalette('dark', deriveDarkFromLight(draft.value.light));
  notice.value = 'Dark palette derived from the light one.';
}

function fixContrast(): void {
  patchPalette(mode.value, autoFixPalette(draft.value[mode.value], mode.value));
}

function randomise(): void {
  draft.value = { ...draft.value, preset: 'custom', light: randomPalette('light'), dark: randomPalette('dark') };
}

function swapModes(): void {
  draft.value = { ...draft.value, preset: 'custom', light: { ...draft.value.dark }, dark: { ...draft.value.light } };
}

function setTextScale(textScale: TextScale): void {
  draft.value = { ...draft.value, options: { ...draft.value.options, textScale } };
}

function setMotion(motion: MotionLevel): void {
  draft.value = { ...draft.value, options: { ...draft.value.options, motion } };
}

// ---- undo / redo ---------------------------------------------------------------------------

const history = ref<string[]>([]);
const cursor = ref(0);
let restoring = false;
let snapshotTimer: ReturnType<typeof setTimeout> | undefined;

const canUndo = computed(() => cursor.value > 0);
const canRedo = computed(() => cursor.value < history.value.length - 1);

function resetHistory(): void {
  history.value = [JSON.stringify(draft.value)];
  cursor.value = 0;
}

function recordSnapshot(): void {
  if (restoring) return;
  clearTimeout(snapshotTimer);
  // Debounced so dragging a colour picker is one undo step, not hundreds.
  snapshotTimer = setTimeout(() => {
    const snap = JSON.stringify(draft.value);
    if (snap === history.value[cursor.value]) return;
    history.value = [...history.value.slice(0, cursor.value + 1), snap].slice(-60);
    cursor.value = history.value.length - 1;
  }, 350);
}

function goTo(index: number): void {
  const snap = history.value[index];
  if (snap === undefined) return;
  clearTimeout(snapshotTimer);
  restoring = true;
  cursor.value = index;
  draft.value = JSON.parse(snap) as ThemeConfig;
  void Promise.resolve().then(() => {
    restoring = false;
  });
}

const undo = (): void => goTo(cursor.value - 1);
const redo = (): void => goTo(cursor.value + 1);

function onKey(e: KeyboardEvent): void {
  const mod = e.ctrlKey || e.metaKey;
  if (!mod) return;
  const key = e.key.toLowerCase();
  const inField = ['INPUT', 'TEXTAREA', 'SELECT'].includes((e.target as HTMLElement | null)?.tagName ?? '');
  if (key === 's') {
    e.preventDefault();
    if (canSave.value) void publish();
  } else if (key === 'z' && !inField) {
    e.preventDefault();
    if (e.shiftKey) redo();
    else undo();
  } else if (key === 'y' && !inField) {
    e.preventDefault();
    redo();
  }
}

function onBeforeUnload(e: BeforeUnloadEvent): void {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = '';
  }
}

function applyColourTheme(id: string): void {
  const p = THEME_PRESETS.find((x) => x.id === id);
  if (p) draft.value = clone({ ...draft.value, preset: id, light: p.config.light, dark: p.config.dark });
}

function setRadius(radius: RadiusKind): void {
  draft.value = { ...draft.value, border: { ...draft.value.border, radius } };
}

function setBorderWidth(width: BorderWidth): void {
  draft.value = { ...draft.value, border: { ...draft.value.border, width } };
}

function setShadow(style: ShadowStyle): void {
  draft.value = { ...draft.value, shadow: { style } };
}

/** Put one section back to the built-in values without touching the others. */
function resetSection(section: Section): void {
  const d = clone(DEFAULT_THEME);
  const cur = draft.value;
  if (section === 'colours') draft.value = { ...cur, preset: 'atomic', light: d.light, dark: d.dark };
  else if (section === 'fonts') draft.value = { ...cur, fonts: d.fonts };
  else if (section === 'style') draft.value = { ...cur, style: d.style };
  else if (section === 'options') draft.value = { ...cur, options: d.options };
  else if (section === 'borders') draft.value = { ...cur, border: d.border };
  else draft.value = { ...cur, shadow: d.shadow };
}

function touch(): void {
  if (draft.value.preset !== 'custom') draft.value = { ...draft.value, preset: 'custom' };
}

/** Turn the "backend does not know this endpoint" failure into something actionable. */
function explain(e: unknown): ErrorInfo {
  const info = errorInfo(e);
  if (e instanceof ApiError && (e.status === 404 || e.status === 405 || e.status === 400)) {
    return {
      message:
        'The server did not recognise the theme API (HTTP ' + e.status + '). product-service and the gateway are probably running an older build: ' +
        'rebuild and restart both, then try again. Your edits are kept here.',
      correlationId: info.correlationId,
    };
  }
  return info;
}

async function publish(): Promise<void> {
  error.value = null;
  notice.value = '';
  saving.value = true;
  try {
    const res = await saveThemeConfig(draft.value);
    setPublished(res.config);
    publishedAt.value = res.updatedAt;
    setBaseline(res.config ? normalizeTheme(res.config) : DEFAULT_THEME);
    notice.value = 'Published. The storefront picks it up on its next page load.';
  } catch (e: unknown) {
    error.value = explain(e);
  } finally {
    saving.value = false;
  }
}

async function resetToBuiltIn(): Promise<void> {
  if (!window.confirm('Remove the custom theme and go back to the built-in Space-Age Atomic look?')) return;
  error.value = null;
  notice.value = '';
  saving.value = true;
  try {
    await resetThemeConfig();
    setPublished(null);
    setBaseline(DEFAULT_THEME);
    notice.value = 'Reset to the built-in theme.';
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    saving.value = false;
  }
}

function revert(): void {
  setBaseline(JSON.parse(baseline.value) as ThemeConfig);
  resetHistory();
}

function exportJson(): void {
  jsonText.value = JSON.stringify(draft.value, null, 2);
  jsonError.value = '';
}

function importJson(): void {
  jsonError.value = '';
  try {
    draft.value = normalizeTheme(JSON.parse(jsonText.value));
    notice.value = 'Config applied to the editor. Review it, then publish.';
  } catch {
    jsonError.value = 'That is not valid JSON.';
  }
}

async function copyJson(): Promise<void> {
  try {
    await navigator.clipboard.writeText(jsonText.value);
    notice.value = 'Copied to clipboard.';
  } catch {
    jsonError.value = 'Clipboard unavailable; select and copy manually.';
  }
}

// Live preview on the console itself.
watch(
  draft,
  (cfg) => {
    paintTheme(cfg);
    recordSnapshot();
  },
  { deep: true },
);

function restoreConsole(): void {
  paintTheme(published.value);
}

onMounted(() => {
  window.addEventListener('keydown', onKey);
  window.addEventListener('beforeunload', onBeforeUnload);
  void load();
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey);
  window.removeEventListener('beforeunload', onBeforeUnload);
  clearTimeout(snapshotTimer);
  restoreConsole();
});
onBeforeRouteLeave(() => {
  if (dirty.value && !window.confirm('You have unpublished theme changes. Leave and discard them?')) return false;
  return true;
});
</script>

<template>
  <section class="max-w-6xl">
    <header class="mb-3">
      <h1 class="mb-1 text-2xl font-semibold">Theme &amp; branding</h1>
      <p class="text-sm opacity-70">
        Design style, colours, fonts, borders, shadows, name and logo for the storefront and this console. Changes
        preview live here and go public when you publish.
      </p>
    </header>

    <div class="action-bar" role="toolbar" aria-label="Theme actions">
      <span class="text-sm" role="status" data-test="status">
        <span v-if="dirty" class="badge badge-warning badge-sm mr-1">Unpublished changes</span>
        <span v-else class="badge badge-success badge-sm mr-1">Up to date</span>
        <span v-if="publishedAt" class="hidden opacity-60 sm:inline">Last published {{ new Date(publishedAt).toLocaleString() }}</span>
        <span v-else class="hidden opacity-60 sm:inline">Nothing published yet (built-in theme)</span>
      </span>
      <span class="flex flex-wrap items-center gap-1">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!canUndo" title="Undo (Ctrl+Z)" aria-label="Undo" @click="undo">↶</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!canRedo" title="Redo (Ctrl+Shift+Z)" aria-label="Redo" @click="redo">↷</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="saving" @click="resetToBuiltIn">Reset to built-in</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!dirty || saving" @click="revert">Discard</button>
        <button class="btn btn-primary btn-sm" type="button" :disabled="!canSave" title="Publish (Ctrl+S)" data-test="publish" @click="publish">
          <span v-if="saving" class="loading loading-spinner loading-xs" aria-hidden="true" />
          Publish
        </button>
      </span>
    </div>

    <ProblemAlert v-if="error" class="mb-3" :message="error.message" :correlation-id="error.correlationId" />
    <div v-if="notice" class="alert alert-success mb-3" role="status">{{ notice }}</div>

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>

    <div v-else class="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <div class="flex min-w-0 flex-col gap-4">
        <!-- Presets -->
        <div class="data-card">
          <h2 class="mb-1 text-lg font-semibold">Complete looks</h2>
          <p class="mb-3 text-sm opacity-70">
            Sets colours, fonts, borders and shadows in one go. Every section below can then be changed on its own.
          </p>
          <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3" role="list">
            <button
              v-for="p in THEME_PRESETS"
              :key="p.id"
              type="button"
              role="listitem"
              class="preset-card text-left"
              :class="{ 'preset-active': draft.preset === p.id }"
              :aria-pressed="draft.preset === p.id"
              :data-test="`preset-${p.id}`"
              @click="applyPreset(p.id)"
            >
              <span class="flex h-10 overflow-hidden rounded-md border border-base-300" aria-hidden="true">
                <span class="flex-1" :style="{ background: p.config.light.background }" />
                <span class="flex-1" :style="{ background: p.config.light.primary }" />
                <span class="flex-1" :style="{ background: p.config.light.accent }" />
                <span class="flex-1" :style="{ background: p.config.dark.background }" />
                <span class="flex-1" :style="{ background: p.config.dark.primary }" />
              </span>
              <span class="mt-2 block text-sm font-semibold">{{ p.label }}</span>
              <span class="block text-xs opacity-70">{{ p.bestFor }}</span>
              <span class="mt-1 block text-[11px] opacity-60">
                {{ p.config.fonts.display }} · {{ p.config.border.radius }} corners · {{ p.config.shadow.style }} shadow
              </span>
            </button>
          </div>
        </div>

        <!-- Design style -->
        <div class="data-card">
          <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
            <h2 class="flex items-center gap-2 text-lg font-semibold">
              Design style
              <span v-if="sectionDirty.style" class="badge badge-warning badge-sm">Changed</span>
            </h2>
            <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('style')">Reset style</button>
          </div>
          <p class="mb-3 text-sm opacity-70">
            The design language: how cards, panels and buttons are drawn (glass, soft-extruded, flat, hard-edged...).
            Material styles (Glass, Liquid Glass, Neumorphism, Claymorphism, Skeuomorphism) draw their own card shadows.
          </p>
          <label class="mb-3 flex cursor-pointer items-center gap-2 text-sm">
            <input v-model="applyKitLook" type="checkbox" class="checkbox checkbox-sm" data-test="apply-kit" />
            Also apply the style's colours, fonts, corners, outlines and shadow
          </label>
          <div class="grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-3" role="list">
            <button
              type="button"
              role="listitem"
              class="option-row"
              :class="{ 'chip-active': draft.style.id === 'default' }"
              :aria-pressed="draft.style.id === 'default'"
              data-test="style-default"
              @click="resetSection('style')"
            >
              <span class="style-dot" style="background: var(--color-base-300)" aria-hidden="true" />
              <span class="text-sm font-medium">Built-in</span>
              <span class="text-xs opacity-60">Space-Age Atomic as shipped</span>
            </button>
            <button
              v-for="k in STYLE_KITS"
              :key="k.id"
              type="button"
              role="listitem"
              class="option-row"
              :class="{ 'chip-active': draft.style.id === k.id }"
              :aria-pressed="draft.style.id === k.id"
              :title="k.bestFor"
              :data-test="`style-${k.id}`"
              @click="applyStyle(k.id)"
            >
              <span class="style-dot" aria-hidden="true">
                <span :style="{ background: k.light.background }" />
                <span :style="{ background: k.light.primary }" />
                <span :style="{ background: k.dark.background }" />
                <span :style="{ background: k.dark.primary }" />
              </span>
              <span class="text-sm font-medium">{{ k.label }}</span>
              <span class="text-xs opacity-60">{{ k.about }}</span>
            </button>
          </div>
        </div>

        <!-- Brand -->
        <div class="data-card">
          <h2 class="mb-3 text-lg font-semibold">Branding</h2>
          <div class="grid gap-3 sm:grid-cols-2">
            <label class="form-control">
              <span class="label-text mb-1 text-sm">Store name</span>
              <input
                v-model="draft.brand.name"
                class="input input-bordered input-sm"
                maxlength="40"
                data-test="brand-name"
              />
            </label>
            <label class="form-control">
              <span class="label-text mb-1 text-sm">Tagline</span>
              <input v-model="draft.brand.tagline" class="input input-bordered input-sm" maxlength="120" />
            </label>
            <label class="form-control sm:col-span-2">
              <span class="label-text mb-1 text-sm">Logo URL (https:// or /path, square works best)</span>
              <input
                v-model="draft.brand.logoUrl"
                class="input input-bordered input-sm"
                placeholder="https://cdn.example.com/logo.svg - leave empty for the built-in mark"
                maxlength="500"
              />
            </label>
          </div>
        </div>

        <!-- Colours -->
        <div class="data-card">
          <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h2 class="flex items-center gap-2 text-lg font-semibold">
              Colours
              <span v-if="sectionDirty.colours" class="badge badge-warning badge-sm">Changed</span>
            </h2>
            <div role="tablist" class="tabs tabs-box tabs-sm">
              <button
                v-for="m in ['light', 'dark'] as const"
                :key="m"
                role="tab"
                type="button"
                class="tab"
                :class="{ 'tab-active': mode === m }"
                :aria-selected="mode === m"
                @click="mode = m"
              >
                {{ m === 'light' ? 'Light mode' : 'Dark mode' }}
              </button>
            </div>
          </div>
          <div class="mb-3 flex flex-wrap gap-2" role="list" aria-label="Colour themes">
            <button
              v-for="p in THEME_PRESETS"
              :key="p.id"
              type="button"
              role="listitem"
              class="colour-chip"
              :class="{ 'chip-active': draft.preset === p.id }"
              :aria-pressed="draft.preset === p.id"
              :title="p.label"
              @click="applyColourTheme(p.id)"
            >
              <span class="flex h-4 w-12 overflow-hidden rounded" aria-hidden="true">
                <span class="flex-1" :style="{ background: p.config[mode].background }" />
                <span class="flex-1" :style="{ background: p.config[mode].primary }" />
                <span class="flex-1" :style="{ background: p.config[mode].accent }" />
              </span>
              <span class="text-xs">{{ p.label }}</span>
            </button>
          </div>
          <div class="grid gap-3 sm:grid-cols-2">
            <div v-for="k in PALETTE_KEYS" :key="k" class="flex items-center gap-3">
              <input
                type="color"
                class="color-swatch"
                :value="draft[mode][k]"
                :aria-label="`${PALETTE_LABELS[k].label} colour`"
                @input="setColor(k, ($event.target as HTMLInputElement).value)"
              />
              <div class="min-w-0 flex-1">
                <div class="text-sm font-medium">{{ PALETTE_LABELS[k].label }}</div>
                <div class="truncate text-xs opacity-60">{{ PALETTE_LABELS[k].hint }}</div>
              </div>
              <input
                class="input input-bordered input-xs w-24 font-mono"
                :value="draft[mode][k]"
                maxlength="7"
                spellcheck="false"
                :aria-label="`${PALETTE_LABELS[k].label} hex`"
                @change="setColor(k, ($event.target as HTMLInputElement).value)"
              />
            </div>
          </div>
          <div class="tools-box">
            <span class="label-text text-sm font-medium">Colour tools</span>
            <div class="flex flex-wrap items-center gap-2">
              <input v-model="brandColour" type="color" class="color-swatch" aria-label="Brand colour" />
              <button class="btn btn-outline btn-xs" type="button" data-test="gen-mode" @click="generateFromBrand(false)">
                Generate {{ mode }} palette from this colour
              </button>
              <button class="btn btn-outline btn-xs" type="button" data-test="gen-both" @click="generateFromBrand(true)">Generate both</button>
            </div>
            <div class="flex flex-wrap gap-2">
              <button class="btn btn-ghost btn-xs" type="button" @click="deriveDark">Derive dark from light</button>
              <button class="btn btn-ghost btn-xs" type="button" @click="fixContrast">Auto-fix {{ mode }} contrast</button>
              <button class="btn btn-ghost btn-xs" type="button" @click="swapModes">Swap light ↔ dark</button>
              <button class="btn btn-ghost btn-xs" type="button" @click="copyPaletteToOther">Copy {{ mode }} to {{ mode === 'light' ? 'dark' : 'light' }}</button>
              <button class="btn btn-ghost btn-xs" type="button" @click="randomise">🎲 Surprise me</button>
              <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('colours')">Reset colours</button>
            </div>
          </div>

          <ul class="mt-3 grid gap-1 text-xs sm:grid-cols-2" aria-label="Contrast checks">
            <li v-for="c in checks" :key="c.label" class="flex items-center justify-between rounded px-2 py-1"
              :class="c.ok ? 'bg-success/10' : 'bg-warning/20'">
              <span>{{ c.label }}</span>
              <span class="font-mono">{{ c.ratio.toFixed(1) }}:1 {{ c.ok ? '✓' : `(needs ${c.min})` }}</span>
            </li>
          </ul>
          <p v-if="failing.length" class="mt-2 text-xs text-warning" role="status">
            {{ failing.length }} pairing{{ failing.length > 1 ? 's' : '' }} below accessibility guidance in
            {{ mode }} mode. You can still publish, but some text will be hard to read.
          </p>
        </div>

        <!-- Fonts -->
        <div class="data-card">
          <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
            <h2 class="flex items-center gap-2 text-lg font-semibold">
              Fonts
              <span v-if="sectionDirty.fonts" class="badge badge-warning badge-sm">Changed</span>
            </h2>
            <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('fonts')">Reset fonts</button>
          </div>
          <p class="mb-3 text-sm opacity-70">Heading and body typefaces for the storefront and this console.</p>
          <div class="mb-3">
            <span class="label-text mb-1 block text-sm">Font pairings</span>
            <div class="flex flex-wrap gap-2" role="list">
              <button
                v-for="fp in FONT_PAIRS"
                :key="fp.id"
                type="button"
                role="listitem"
                class="btn btn-xs"
                :class="draft.fonts.display === fp.display && draft.fonts.body === fp.body ? 'btn-primary' : 'btn-outline'"
                :title="fp.vibe"
                :aria-pressed="draft.fonts.display === fp.display && draft.fonts.body === fp.body"
                @click="applyFontPair(fp.id)"
              >
                {{ fp.label }}
              </button>
            </div>
          </div>
          <div class="grid gap-3 sm:grid-cols-2">
            <label class="form-control">
              <span class="label-text mb-1 text-sm">Heading font</span>
              <select v-model="draft.fonts.display" class="select select-bordered select-sm" @change="touch">
                <option v-for="f in FONT_OPTIONS" :key="f" :value="f">{{ f }}</option>
              </select>
            </label>
            <label class="form-control">
              <span class="label-text mb-1 text-sm">Body font</span>
              <select v-model="draft.fonts.body" class="select select-bordered select-sm" @change="touch">
                <option v-for="f in FONT_OPTIONS" :key="f" :value="f">{{ f }}</option>
              </select>
            </label>
          </div>
        </div>

        <!-- Borders -->
        <div class="data-card">
          <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
            <h2 class="flex items-center gap-2 text-lg font-semibold">
              Borders
              <span v-if="sectionDirty.borders" class="badge badge-warning badge-sm">Changed</span>
            </h2>
            <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('borders')">Reset borders</button>
          </div>
          <p class="mb-3 text-sm opacity-70">Corner roundness and outline thickness. Pills and circles stay round.</p>
          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <span class="label-text mb-1 block text-sm">Corners</span>
              <div class="flex flex-col gap-2">
                <button
                  v-for="r in RADIUS_KINDS"
                  :key="r.value"
                  type="button"
                  class="option-row"
                  :class="{ 'chip-active': draft.border.radius === r.value }"
                  :aria-pressed="draft.border.radius === r.value"
                  :data-test="`radius-${r.value}`"
                  @click="setRadius(r.value)"
                >
                  <span class="option-swatch" :style="{ borderRadius: r.value === 'sharp' ? '2px' : r.value === 'round' ? '14px' : '8px' }" aria-hidden="true" />
                  <span class="text-sm font-medium">{{ r.label }}</span>
                  <span class="text-xs opacity-60">{{ r.hint }}</span>
                </button>
              </div>
            </div>
            <div>
              <span class="label-text mb-1 block text-sm">Outline thickness</span>
              <div class="flex flex-col gap-2">
                <button
                  v-for="w in BORDER_WIDTHS"
                  :key="w.value"
                  type="button"
                  class="option-row"
                  :class="{ 'chip-active': draft.border.width === w.value }"
                  :aria-pressed="draft.border.width === w.value"
                  :data-test="`width-${w.value}`"
                  @click="setBorderWidth(w.value)"
                >
                  <span
                    class="option-swatch"
                    :style="{ borderWidth: w.value === 'thin' ? '1px' : w.value === 'bold' ? '3px' : '2px' }"
                    aria-hidden="true"
                  />
                  <span class="text-sm font-medium">{{ w.label }}</span>
                  <span class="text-xs opacity-60">{{ w.hint }}</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Shadows -->
        <div class="data-card">
          <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
            <h2 class="flex items-center gap-2 text-lg font-semibold">
              Shadows
              <span v-if="sectionDirty.shadows" class="badge badge-warning badge-sm">Changed</span>
            </h2>
            <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('shadows')">Reset shadows</button>
          </div>
          <p class="mb-3 text-sm opacity-70">Elevation of cards, buttons and menus.</p>
          <div class="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
            <button
              v-for="sh in SHADOW_STYLES"
              :key="sh.value"
              type="button"
              class="option-row"
              :class="{ 'chip-active': draft.shadow.style === sh.value }"
              :aria-pressed="draft.shadow.style === sh.value"
              :data-test="`shadow-${sh.value}`"
              @click="setShadow(sh.value)"
            >
              <span class="shadow-swatch" :class="`sw-${sh.value}`" aria-hidden="true" />
              <span class="text-sm font-medium">{{ sh.label }}</span>
              <span class="text-xs opacity-60">{{ sh.hint }}</span>
            </button>
          </div>
        </div>

        <!-- Comfort options -->
        <div class="data-card">
          <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
            <h2 class="flex items-center gap-2 text-lg font-semibold">
              Comfort options
              <span v-if="sectionDirty.options" class="badge badge-warning badge-sm">Changed</span>
            </h2>
            <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('options')">Reset options</button>
          </div>
          <p class="mb-3 text-sm opacity-70">Global text size and motion, applied to the storefront and this console.</p>
          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <span class="label-text mb-1 block text-sm">Text size</span>
              <div class="flex flex-col gap-2">
                <button v-for="t in TEXT_SCALES" :key="t.value" type="button" class="option-row"
                  :class="{ 'chip-active': draft.options.textScale === t.value }" :aria-pressed="draft.options.textScale === t.value"
                  :data-test="`scale-${t.value}`" @click="setTextScale(t.value)">
                  <span class="option-letter" :style="{ fontSize: t.value === 'small' ? '0.8rem' : t.value === 'large' ? '1.25rem' : '1rem' }" aria-hidden="true">Aa</span>
                  <span class="text-sm font-medium">{{ t.label }}</span>
                  <span class="text-xs opacity-60">{{ t.hint }}</span>
                </button>
              </div>
            </div>
            <div>
              <span class="label-text mb-1 block text-sm">Motion</span>
              <div class="flex flex-col gap-2">
                <button v-for="m in MOTION_LEVELS" :key="m.value" type="button" class="option-row"
                  :class="{ 'chip-active': draft.options.motion === m.value }" :aria-pressed="draft.options.motion === m.value"
                  :data-test="`motion-${m.value}`" @click="setMotion(m.value)">
                  <span class="option-letter" aria-hidden="true">{{ m.value === 'full' ? '≋' : m.value === 'reduced' ? '≈' : '═' }}</span>
                  <span class="text-sm font-medium">{{ m.label }}</span>
                  <span class="text-xs opacity-60">{{ m.hint }}</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Code config -->
        <div class="data-card">
          <h2 class="mb-1 text-lg font-semibold">Code configuration</h2>
          <p class="mb-3 text-sm opacity-70">
            Extra CSS applied on the storefront and this console, after the generated theme. No
            <code>@import</code>, <code>url(...)</code> or markup; max 4000 characters.
          </p>
          <label class="form-control">
            <span class="label-text mb-1 text-sm">Custom CSS</span>
            <textarea
              v-model="draft.customCss"
              class="textarea textarea-bordered h-32 font-mono text-xs"
              spellcheck="false"
              placeholder="/* e.g. .font-display { letter-spacing: 0.04em; } */"
              data-test="custom-css"
              @input="touch"
            />
          </label>
          <p v-if="cssProblem" class="mt-1 text-xs text-error" role="alert">{{ cssProblem }}</p>

          <details class="mt-3">
            <summary class="cursor-pointer text-sm font-medium">Config as JSON (copy between environments)</summary>
            <div class="mt-2 flex flex-col gap-2">
              <textarea
                v-model="jsonText"
                class="textarea textarea-bordered h-40 font-mono text-xs"
                spellcheck="false"
                aria-label="Theme config JSON"
              />
              <p v-if="jsonError" class="text-xs text-error" role="alert">{{ jsonError }}</p>
              <div class="flex flex-wrap gap-2">
                <button class="btn btn-ghost btn-xs" type="button" @click="exportJson">Load current config</button>
                <button class="btn btn-ghost btn-xs" type="button" :disabled="!jsonText" @click="copyJson">Copy</button>
                <button class="btn btn-outline btn-xs" type="button" :disabled="!jsonText" @click="importJson">
                  Apply JSON to editor
                </button>
              </div>
            </div>
          </details>
        </div>
      </div>

      <!-- Preview -->
      <aside class="min-w-0 lg:sticky lg:top-4 lg:self-start" aria-label="Storefront preview">
        <div class="data-card">
          <div class="mb-2 flex items-center justify-between">
            <h2 class="text-sm font-semibold">Storefront preview ({{ mode }})</h2>
            <span v-if="dirty" class="badge badge-warning badge-sm">Unpublished</span>
          </div>
          <div class="mock" :class="`pb-${previewFx.backdrop} ph-${previewFx.heading} pbtn-${previewFx.button}`" :style="previewStyle" data-test="preview">
            <div class="mock-header">
              <img v-if="draft.brand.logoUrl" :src="draft.brand.logoUrl" alt="" class="mock-logo" />
              <span v-else class="mock-dot" aria-hidden="true" />
              <strong class="mock-brand">{{ draft.brand.name || 'Store name' }}</strong>
            </div>
            <div class="mock-body">
              <p class="mock-tagline">{{ draft.brand.tagline || 'Your tagline here' }}</p>
              <h3 class="mock-title">Everyday essentials</h3>
              <p class="mock-copy">Honest deals on the things you actually use, delivered fast.</p>
              <div class="mock-row">
                <button type="button" class="mock-btn mock-btn-primary" tabindex="-1">Shop now</button>
                <button type="button" class="mock-btn mock-btn-accent" tabindex="-1">Deals</button>
              </div>
              <div class="mock-card" :class="`pv-${previewFx.surface}`">
                <div class="mock-img" aria-hidden="true" />
                <div>
                  <div class="mock-name">Trail water bottle</div>
                  <div class="mock-muted">★★★★☆ (128)</div>
                  <div><span class="mock-price">$18.00</span> <span class="mock-sale">-25%</span></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.preset-card {
  border: 2px solid var(--color-base-300);
  border-radius: var(--radius-field);
  padding: 0.6rem;
  background: var(--color-base-100);
  transition: border-color 150ms ease;
}
.preset-card:hover,
.preset-card:focus-visible {
  border-color: var(--color-primary);
}
.preset-active {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px var(--color-primary);
}
.colour-chip {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
  padding: 0.35rem 0.5rem;
  border: 2px solid var(--color-base-300);
  border-radius: var(--radius-field);
  background: var(--color-base-100);
}
.colour-chip:hover,
.colour-chip:focus-visible,
.chip-active {
  border-color: var(--color-primary);
}
.chip-active {
  box-shadow: 0 0 0 2px var(--color-primary);
}
.option-row {
  display: grid;
  grid-template-columns: 2.25rem auto;
  grid-template-rows: auto auto;
  column-gap: 0.6rem;
  align-items: center;
  text-align: left;
  padding: 0.45rem 0.6rem;
  border: 2px solid var(--color-base-300);
  border-radius: var(--radius-field);
  background: var(--color-base-100);
}
.option-row:hover,
.option-row:focus-visible {
  border-color: var(--color-primary);
}
.option-row > :nth-child(1) {
  grid-row: 1 / span 2;
}
.option-row > :nth-child(3) {
  grid-column: 2;
}
.option-swatch {
  width: 2rem;
  height: 2rem;
  border: 2px solid var(--color-base-content);
}
.shadow-swatch {
  width: 2rem;
  height: 2rem;
  border-radius: 6px;
  background: var(--color-base-100);
  border: 1px solid var(--color-base-300);
}
.sw-none { box-shadow: none; }
.sw-soft { box-shadow: 0 2px 8px rgb(0 0 0 / 0.25); }
.sw-crisp { box-shadow: 2px 2px 0 var(--color-base-content); }
.sw-retro { box-shadow: 4px 4px 0 var(--color-base-content); }
.sw-lifted { box-shadow: 0 10px 18px -6px rgb(0 0 0 / 0.5); }
.action-bar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin-bottom: 1rem;
  padding: 0.5rem 0.75rem;
  background: var(--color-base-100);
  border: var(--cw-bw, 2px) solid var(--color-base-300);
  border-radius: var(--radius-field);
}
.tools-box {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-top: 0.75rem;
  padding: 0.6rem;
  border: 1px dashed var(--color-base-300);
  border-radius: var(--radius-field);
}
.option-letter {
  display: grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  font-weight: 700;
}
.style-dot {
  display: grid;
  grid-template-columns: 1fr 1fr;
  width: 2rem;
  height: 2rem;
  overflow: hidden;
  border-radius: 6px;
  border: 1px solid var(--color-base-300);
}
.style-dot > span {
  display: block;
}
.color-swatch {
  width: 2.25rem;
  height: 2.25rem;
  padding: 0;
  border: 2px solid var(--color-base-300);
  border-radius: 0.5rem;
  background: none;
  cursor: pointer;
}

/* Mock storefront: driven entirely by the scoped --p-* variables set inline. */
.mock {
  background: var(--p-bg);
  color: var(--p-text);
  font-family: var(--p-body);
  border: 1px solid var(--p-outline);
  border-radius: var(--p-radius);
  overflow: hidden;
  font-size: 0.8rem;
}
.mock-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.6rem 0.8rem;
  background: var(--p-primary);
  color: var(--p-on-primary);
  font-family: var(--p-display);
}
.mock-logo {
  width: 1.6rem;
  height: 1.6rem;
  object-fit: contain;
}
.mock-dot {
  width: 1.2rem;
  height: 1.2rem;
  border-radius: 9999px;
  background: var(--p-accent);
  border: 2px solid var(--p-ink);
}
.mock-body {
  padding: 0.9rem 0.8rem;
}
.mock-tagline {
  color: var(--p-muted);
  font-size: 0.7rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.mock-title {
  font-family: var(--p-display);
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0.15rem 0;
}
.mock-copy {
  color: var(--p-muted);
  margin-bottom: 0.7rem;
}
.mock-row {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 0.8rem;
}
.mock-btn {
  border-radius: var(--p-radius);
  padding: 0.35rem 0.8rem;
  font-weight: 600;
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
.mock-card {
  display: flex;
  gap: 0.7rem;
  padding: 0.6rem;
  background: var(--p-surface);
  border: var(--p-bw) solid var(--p-outline);
  border-radius: var(--p-radius);
  box-shadow: var(--p-shadow);
}
/* Design-style previews: same recipes as themeEngine's surface/backdrop/heading/button rules. */
.pv-glass {
  background: color-mix(in srgb, var(--p-surface) 58%, transparent);
  backdrop-filter: blur(12px) saturate(150%);
  box-shadow: 0 8px 24px -8px var(--p-lo);
}
.pv-liquid {
  background: linear-gradient(135deg, color-mix(in srgb, var(--p-surface) 62%, transparent), color-mix(in srgb, var(--p-surface) 38%, transparent));
  backdrop-filter: blur(18px) saturate(190%);
  border-color: color-mix(in srgb, white 45%, transparent);
  box-shadow: inset 0 1px 0 color-mix(in srgb, white 70%, transparent), 0 14px 30px -12px var(--p-lo);
}
.pv-neu {
  background: var(--p-bg);
  border-color: transparent;
  box-shadow: 8px 8px 16px var(--p-lo), -8px -8px 16px var(--p-hi);
}
.pv-clay {
  border-color: transparent;
  box-shadow: inset -5px -5px 10px color-mix(in srgb, var(--p-text) 10%, transparent), inset 5px 5px 10px var(--p-hi), 8px 12px 22px -6px var(--p-lo);
}
.pv-skeuo {
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
.mock-img {
  width: 3.4rem;
  height: 3.4rem;
  flex: none;
  border-radius: calc(var(--p-radius) * 0.6);
  background: linear-gradient(135deg, var(--p-primary), var(--p-accent));
}
.mock-name {
  font-weight: 600;
}
.mock-muted {
  color: var(--p-muted);
  font-size: 0.7rem;
}
.mock-price {
  font-weight: 700;
}
.mock-sale {
  background: var(--p-deal);
  color: var(--p-on-error);
  border-radius: 9999px;
  padding: 0 0.4rem;
  font-size: 0.7rem;
  font-weight: 700;
}
</style>
