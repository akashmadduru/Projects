<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import ThemePreview from '@/components/theme/ThemePreview.vue';
import { paintTheme, setPublished, useBranding } from '@/composables/useBranding';
import { useThemeDrafts } from '@/composables/useThemeDrafts';
import { errorInfo } from '@/lib/adminApi';
import { extractColours } from '@/lib/imageColours';
import { ApiError } from '@/lib/problem';
import {
  BORDER_WIDTHS,
  DEFAULT_THEME,
  FONT_OPTIONS,
  FONT_PAIRS,
  MOTION_LEVELS,
  PALETTE_KEYS,
  PREVIEW_HASH_KEY,
  RADIUS_KINDS,
  SHADOW_STYLES,
  STYLE_FX,
  TEXT_SCALES,
  autoFixPalette,
  buildThemeCss,
  contrastChecks,
  customCssProblem,
  deriveDarkFromLight,
  encodeThemeForUrl,
  generatePalette,
  isHex,
  normalizeTheme,
  randomPalette,
  type BorderWidth,
  type MotionLevel,
  type PaletteKey,
  type RadiusKind,
  type ShadowStyle,
  type StyleId,
  type TextScale,
  type ThemeConfig,
  type ThemePalette,
} from '@/lib/themeEngine';
import { THEME_PRESETS } from '@/lib/themePresets';
import { STYLE_KITS } from '@/lib/themeStyles';
import { getThemeConfig, resetThemeConfig, saveThemeConfig } from '@/lib/trustApi';

/**
 * Theme & branding editor.
 *
 * Edits are painted LIVE on this console (so the admin sees exactly what they will get) but only
 * reach the storefront - and everyone else's console - on Publish. The working copy autosaves to
 * this browser so a crash or accidental tab close loses nothing; leaving the page reverts the live
 * paint to whatever is published.
 */
type ErrorInfo = { message: string; correlationId: string | null };
type Mode = 'light' | 'dark';
type Section = 'style' | 'brand' | 'colours' | 'fonts' | 'borders' | 'shadows' | 'options' | 'css';
type TabId = 'start' | 'looks' | 'brand' | 'colours' | 'type' | 'shape' | 'comfort' | 'advanced';

interface Tab {
  id: TabId;
  label: string;
  icon: string;
  sections: Section[];
}

const TABS: readonly Tab[] = [
  { id: 'start', label: 'Quick start', icon: '✦', sections: [] },
  { id: 'looks', label: 'Looks', icon: '◧', sections: ['style'] },
  { id: 'brand', label: 'Brand', icon: '♛', sections: ['brand'] },
  { id: 'colours', label: 'Colours', icon: '◐', sections: ['colours'] },
  { id: 'type', label: 'Typography', icon: 'Aa', sections: ['fonts'] },
  { id: 'shape', label: 'Shape', icon: '▢', sections: ['borders', 'shadows'] },
  { id: 'comfort', label: 'Comfort', icon: '☺', sections: ['options'] },
  { id: 'advanced', label: 'Advanced', icon: '</>', sections: ['css'] },
];

const PALETTE_LABELS: Record<PaletteKey, { label: string; hint: string }> = {
  primary: { label: 'Primary', hint: 'Buttons, links, header' },
  accent: { label: 'Accent', hint: 'Highlights, badges, secondary actions' },
  background: { label: 'Background', hint: 'Page canvas' },
  surface: { label: 'Surface', hint: 'Cards, menus, inputs' },
  text: { label: 'Text', hint: 'Body copy and lines' },
  danger: { label: 'Danger / deals', hint: 'Errors, discounts, sale tags' },
};

const STOREFRONT_ORIGIN = (import.meta.env.VITE_STOREFRONT_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '');
const TAB_KEY = 'cw_theme_tab_v1';

/** structuredClone throws DataCloneError on Vue reactive proxies; the config is plain JSON, so round-trip it. */
function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T;
}

const { published } = useBranding();
const draftsStore = useThemeDrafts();

// ---- state -----------------------------------------------------------------------------------

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
const applyKitLook = ref(true);
const lookFilter = ref('');
const pendingRestore = ref<ThemeConfig | null>(null);
const imageColours = ref<string[]>([]);
const imageBusy = ref(false);
const draftName = ref('');
const previewWide = ref(false);
const publishDialog = ref<HTMLDialogElement | null>(null);
const activeTab = ref<TabId>(readTab());

function readTab(): TabId {
  try {
    const t = localStorage.getItem(TAB_KEY);
    return TABS.some((x) => x.id === t) ? (t as TabId) : 'start';
  } catch {
    return 'start';
  }
}

watch(activeTab, (t) => {
  try {
    localStorage.setItem(TAB_KEY, t);
  } catch {
    // Per-viewer convenience only.
  }
});

// ---- derived ---------------------------------------------------------------------------------

const baselineCfg = computed(() => JSON.parse(baseline.value) as ThemeConfig);

/** Per-section "changed since last publish" flags: badges on tabs + the publish summary. */
const sectionDirty = computed<Record<Section, boolean>>(() => {
  const base = baselineCfg.value;
  const d = draft.value;
  const diff = (a: unknown, b: unknown) => JSON.stringify(a) !== JSON.stringify(b);
  return {
    style: diff(d.style, base.style),
    brand: diff(d.brand, base.brand),
    colours: diff([d.light, d.dark], [base.light, base.dark]),
    fonts: diff(d.fonts, base.fonts),
    borders: diff(d.border, base.border),
    shadows: diff(d.shadow, base.shadow),
    options: diff(d.options, base.options),
    css: d.customCss !== base.customCss,
  };
});

const SECTION_LABEL: Record<Section, string> = {
  style: 'Design style',
  brand: 'Brand name, tagline or logo',
  colours: 'Colours',
  fonts: 'Fonts',
  borders: 'Borders',
  shadows: 'Shadows',
  options: 'Comfort options',
  css: 'Custom CSS',
};

const changedSections = computed(() =>
  (Object.keys(sectionDirty.value) as Section[]).filter((s) => sectionDirty.value[s]).map((s) => SECTION_LABEL[s]),
);

function tabDirty(tab: Tab): boolean {
  return tab.sections.some((s) => sectionDirty.value[s]);
}

const dirty = computed(() => JSON.stringify(draft.value) !== baseline.value);
const cssProblem = computed(() => customCssProblem(draft.value.customCss));
const checks = computed(() => contrastChecks(draft.value[mode.value]));
const failing = computed(() => checks.value.filter((c) => !c.ok));
const failingBoth = computed(
  () => contrastChecks(draft.value.light).filter((c) => !c.ok).length + contrastChecks(draft.value.dark).filter((c) => !c.ok).length,
);
const canSave = computed(
  () => dirty.value && !cssProblem.value && draft.value.brand.name.trim().length > 0 && !saving.value,
);

const filteredPresets = computed(() => {
  const q = lookFilter.value.trim().toLowerCase();
  return q ? THEME_PRESETS.filter((p) => `${p.label} ${p.bestFor}`.toLowerCase().includes(q)) : THEME_PRESETS;
});
const filteredKits = computed(() => {
  const q = lookFilter.value.trim().toLowerCase();
  return q ? STYLE_KITS.filter((k) => `${k.label} ${k.about} ${k.bestFor}`.toLowerCase().includes(q)) : STYLE_KITS;
});

const storefrontPreviewUrl = computed(() => `${STOREFRONT_ORIGIN}/#${PREVIEW_HASH_KEY}=${encodeThemeForUrl(draft.value)}`);

const activeStyleLabel = computed(() => STYLE_KITS.find((k) => k.id === draft.value.style.id)?.label ?? 'Built-in');
/** Material styles (glass, neumorphic...) paint card shadows themselves; the Shadows setting then only reaches buttons. */
const styleOwnsShadows = computed(() => STYLE_FX[draft.value.style.id].surface !== 'solid');
const activePresetLabel = computed(() => THEME_PRESETS.find((p) => p.id === draft.value.preset)?.label ?? 'Custom');

// ---- load / baseline -------------------------------------------------------------------------

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
    const working = draftsStore.readWorking();
    if (working && JSON.stringify(working) !== baseline.value) pendingRestore.value = working;
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    loading.value = false;
  }
}

function restoreWorking(): void {
  if (!pendingRestore.value) return;
  draft.value = clone(pendingRestore.value);
  pendingRestore.value = null;
  notice.value = 'Your unsaved work is back. Review it, then publish.';
}

function dismissWorking(): void {
  pendingRestore.value = null;
  draftsStore.writeWorking(null);
}

// ---- looks -----------------------------------------------------------------------------------

function applyPreset(id: string): void {
  const p = THEME_PRESETS.find((x) => x.id === id);
  if (!p) return;
  // Keep the merchant's own brand text; a preset swaps the look, not the identity.
  draft.value = clone({ ...p.config, brand: draft.value.brand });
}

function applyColourTheme(id: string): void {
  const p = THEME_PRESETS.find((x) => x.id === id);
  if (p) draft.value = clone({ ...draft.value, preset: id, light: p.config.light, dark: p.config.dark });
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

// ---- colours ---------------------------------------------------------------------------------

function patchPalette(m: Mode, palette: ThemePalette): void {
  draft.value = { ...draft.value, preset: 'custom', [m]: palette };
}

function setColor(key: PaletteKey, value: string): void {
  if (!isHex(value)) return;
  patchPalette(mode.value, { ...draft.value[mode.value], [key]: value.toLowerCase() });
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
  notice.value = 'Palette generated from your brand colour. Tweak any swatch in Colours.';
}

function useAsAccent(hex: string): void {
  if (!isHex(hex)) return;
  draft.value = {
    ...draft.value,
    preset: 'custom',
    light: { ...draft.value.light, accent: hex },
    dark: { ...draft.value.dark, accent: hex },
  };
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

function copyPaletteToOther(): void {
  const from = mode.value;
  const to: Mode = from === 'light' ? 'dark' : 'light';
  patchPalette(to, { ...draft.value[from] });
  notice.value = `Copied the ${from} palette to ${to}. Adjust background/surface/text so it still reads well.`;
}

async function onImagePicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  imageBusy.value = true;
  error.value = null;
  try {
    imageColours.value = await extractColours(file, 6);
    if (imageColours.value.length === 0) notice.value = 'No usable colours found in that image (it may be mostly white, black or grey).';
  } catch (e: unknown) {
    error.value = { message: e instanceof Error ? e.message : 'Could not read that image.', correlationId: null };
  } finally {
    imageBusy.value = false;
    input.value = '';
  }
}

function useImageColour(hex: string): void {
  brandColour.value = hex;
  generateFromBrand(true);
}

// ---- type / shape / comfort ------------------------------------------------------------------

function applyFontPair(id: string): void {
  const p = FONT_PAIRS.find((x) => x.id === id);
  if (p) draft.value = { ...draft.value, preset: 'custom', fonts: { display: p.display, body: p.body } };
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

function setTextScale(textScale: TextScale): void {
  draft.value = { ...draft.value, options: { ...draft.value.options, textScale } };
}

function setMotion(motion: MotionLevel): void {
  draft.value = { ...draft.value, options: { ...draft.value.options, motion } };
}

/** Put one section back to the built-in values without touching the others. */
function resetSection(section: Section): void {
  const d = clone(DEFAULT_THEME);
  const cur = draft.value;
  switch (section) {
    case 'colours':
      draft.value = { ...cur, preset: 'atomic', light: d.light, dark: d.dark };
      break;
    case 'fonts':
      draft.value = { ...cur, fonts: d.fonts };
      break;
    case 'style':
      draft.value = { ...cur, style: d.style };
      break;
    case 'brand':
      draft.value = { ...cur, brand: baselineCfg.value.brand };
      break;
    case 'options':
      draft.value = { ...cur, options: d.options };
      break;
    case 'borders':
      draft.value = { ...cur, border: d.border };
      break;
    case 'shadows':
      draft.value = { ...cur, shadow: d.shadow };
      break;
    case 'css':
      draft.value = { ...cur, customCss: '' };
      break;
  }
}

function touch(): void {
  if (draft.value.preset !== 'custom') draft.value = { ...draft.value, preset: 'custom' };
}

// ---- undo / redo -----------------------------------------------------------------------------

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
  void nextTick(() => {
    restoring = false;
  });
}

const undo = (): void => goTo(cursor.value - 1);
const redo = (): void => goTo(cursor.value + 1);

// ---- publish / reset / drafts ----------------------------------------------------------------

/** Turn the "backend does not know this endpoint" failure into something actionable. */
function explain(e: unknown): ErrorInfo {
  const info = errorInfo(e);
  if (e instanceof ApiError && (e.status === 404 || e.status === 405 || e.status === 400)) {
    return {
      message:
        `The server did not recognise the theme API (HTTP ${e.status}). product-service and the gateway are probably running an older build: ` +
        'rebuild and restart both, then try again. Your edits are kept here.',
      correlationId: info.correlationId,
    };
  }
  return info;
}

function requestPublish(): void {
  if (!canSave.value) return;
  publishDialog.value?.showModal();
}

async function publish(): Promise<void> {
  publishDialog.value?.close();
  error.value = null;
  notice.value = '';
  saving.value = true;
  try {
    const res = await saveThemeConfig(draft.value);
    setPublished(res.config);
    publishedAt.value = res.updatedAt;
    setBaseline(res.config ? normalizeTheme(res.config) : DEFAULT_THEME);
    draftsStore.writeWorking(null);
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
    draftsStore.writeWorking(null);
    notice.value = 'Reset to the built-in theme.';
  } catch (e: unknown) {
    error.value = errorInfo(e);
  } finally {
    saving.value = false;
  }
}

function revert(): void {
  setBaseline(baselineCfg.value);
  draftsStore.writeWorking(null);
  resetHistory();
}

function saveDraft(): void {
  const d = draftsStore.save(draftName.value || `${draft.value.brand.name} · ${activePresetLabel.value}`, draft.value);
  draftName.value = '';
  notice.value = `Saved "${d.name}" in this browser.`;
}

function loadDraft(id: string): void {
  const d = draftsStore.drafts.value.find((x) => x.id === id);
  if (!d) return;
  if (dirty.value && !window.confirm('Replace your current unpublished edits with this saved draft?')) return;
  draft.value = clone(d.config);
  notice.value = `Loaded "${d.name}". Publish when you are happy with it.`;
}

function renameDraft(id: string, current: string): void {
  const name = window.prompt('Draft name', current);
  if (name !== null) draftsStore.rename(id, name);
}

function removeDraft(id: string): void {
  if (window.confirm('Delete this saved draft?')) draftsStore.remove(id);
}

// ---- export / import -------------------------------------------------------------------------

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

async function copyText(text: string, what: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
    notice.value = `${what} copied to clipboard.`;
  } catch {
    error.value = { message: 'Clipboard unavailable; select and copy manually.', correlationId: null };
  }
}

function download(name: string, content: string, type: string): void {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const slug = computed(() => draft.value.brand.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'theme');

function openStorefrontPreview(): void {
  window.open(storefrontPreviewUrl.value, '_blank', 'noopener');
}

// ---- keyboard / lifecycle --------------------------------------------------------------------

function onKey(e: KeyboardEvent): void {
  const mod = e.ctrlKey || e.metaKey;
  if (!mod) return;
  const key = e.key.toLowerCase();
  const inField = ['INPUT', 'TEXTAREA', 'SELECT'].includes((e.target as HTMLElement | null)?.tagName ?? '');
  if (key === 's') {
    e.preventDefault();
    requestPublish();
  } else if (key === 'z' && !inField) {
    e.preventDefault();
    if (e.shiftKey) redo();
    else undo();
  } else if (key === 'y' && !inField) {
    e.preventDefault();
    redo();
  }
}

function onTabKey(e: KeyboardEvent): void {
  const i = TABS.findIndex((t) => t.id === activeTab.value);
  if (e.key === 'ArrowRight') activeTab.value = TABS[(i + 1) % TABS.length]!.id;
  else if (e.key === 'ArrowLeft') activeTab.value = TABS[(i - 1 + TABS.length) % TABS.length]!.id;
  else if (e.key === 'Home') activeTab.value = TABS[0]!.id;
  else if (e.key === 'End') activeTab.value = TABS[TABS.length - 1]!.id;
  else return;
  e.preventDefault();
  void nextTick(() => document.getElementById(`theme-tab-${activeTab.value}`)?.focus());
}

function onBeforeUnload(e: BeforeUnloadEvent): void {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = '';
  }
}

// Live preview on the console itself + autosave of the working copy.
watch(
  draft,
  (cfg) => {
    paintTheme(cfg);
    recordSnapshot();
    if (!loading.value) draftsStore.writeWorking(dirty.value ? cfg : null);
  },
  { deep: true },
);

onMounted(() => {
  window.addEventListener('keydown', onKey);
  window.addEventListener('beforeunload', onBeforeUnload);
  void load();
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey);
  window.removeEventListener('beforeunload', onBeforeUnload);
  clearTimeout(snapshotTimer);
  paintTheme(published.value);
});
onBeforeRouteLeave(() => {
  if (dirty.value && !window.confirm('You have unpublished theme changes. Leave now? (They stay saved in this browser.)')) return false;
  return true;
});
</script>

<template>
  <section class="theme-editor">
    <header class="mb-3">
      <h1 class="mb-1 text-2xl font-semibold">Theme &amp; branding</h1>
      <p class="text-sm opacity-70">
        One look for the storefront and this console. Changes preview live here and go public when you publish.
      </p>
    </header>

    <!-- Sticky action bar -->
    <div class="action-bar" role="toolbar" aria-label="Theme actions">
      <span class="flex flex-wrap items-center gap-1 text-sm" role="status" data-test="status">
        <span v-if="dirty" class="badge badge-warning badge-sm">Unpublished changes</span>
        <span v-else class="badge badge-success badge-sm">Up to date</span>
        <span v-if="failingBoth" class="badge badge-outline badge-sm" title="Colour pairings below accessibility guidance">⚠ {{ failingBoth }} contrast</span>
        <span v-if="publishedAt" class="hidden opacity-60 md:inline">Last published {{ new Date(publishedAt).toLocaleString() }}</span>
        <span v-else class="hidden opacity-60 md:inline">Nothing published yet (built-in theme)</span>
      </span>
      <span class="flex flex-wrap items-center gap-1">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!canUndo" title="Undo (Ctrl+Z)" aria-label="Undo" @click="undo">↶</button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="!canRedo" title="Redo (Ctrl+Shift+Z)" aria-label="Redo" @click="redo">↷</button>
        <button class="btn btn-ghost btn-sm" type="button" title="Open the real storefront with this draft (not published)" data-test="open-preview" @click="openStorefrontPreview">
          Preview on storefront ↗
        </button>
        <div class="dropdown dropdown-end">
          <button class="btn btn-ghost btn-sm" type="button" tabindex="0" aria-haspopup="menu">More ▾</button>
          <ul class="menu dropdown-content z-30 w-64 rounded-box border border-base-300 bg-base-100 p-2 shadow" role="menu">
            <li><button type="button" @click="copyText(storefrontPreviewUrl, 'Preview link')">Copy preview link</button></li>
            <li><button type="button" @click="draftName = ''; saveDraft()">Save as draft (this browser)</button></li>
            <li><button type="button" @click="download(`${slug}-storefront.css`, buildThemeCss(draft, 'storefront'), 'text/css')">Download storefront CSS</button></li>
            <li><button type="button" @click="download(`${slug}-admin.css`, buildThemeCss(draft, 'admin'), 'text/css')">Download admin CSS</button></li>
            <li><button type="button" @click="download(`${slug}-theme.json`, JSON.stringify(draft, null, 2), 'application/json')">Download config JSON</button></li>
            <li class="menu-title pt-2"><span>Danger zone</span></li>
            <li><button type="button" :disabled="!dirty || saving" @click="revert">Discard unpublished changes</button></li>
            <li><button type="button" class="text-error" :disabled="saving" @click="resetToBuiltIn">Reset to built-in theme</button></li>
          </ul>
        </div>
        <button class="btn btn-primary btn-sm" type="button" :disabled="!canSave" title="Publish (Ctrl+S)" data-test="publish" @click="requestPublish">
          <span v-if="saving" class="loading loading-spinner loading-xs" aria-hidden="true" />
          Publish
        </button>
      </span>
    </div>

    <ProblemAlert v-if="error" class="mb-3" :message="error.message" :correlation-id="error.correlationId" />
    <div v-if="notice" class="alert alert-success mb-3 py-2 text-sm" role="status">
      <span class="flex-1">{{ notice }}</span>
      <button class="btn btn-ghost btn-xs" type="button" aria-label="Dismiss" @click="notice = ''">✕</button>
    </div>
    <div v-if="pendingRestore" class="alert alert-info mb-3 py-2 text-sm" role="status" data-test="restore">
      <span class="flex-1">You have unsaved edits from an earlier session in this browser.</span>
      <button class="btn btn-primary btn-xs" type="button" @click="restoreWorking">Restore them</button>
      <button class="btn btn-ghost btn-xs" type="button" @click="dismissWorking">Discard</button>
    </div>

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>

    <div v-else class="editor-grid" :class="{ 'editor-grid-wide': previewWide }">
      <!-- ===================== Editor ===================== -->
      <div class="min-w-0">
        <div class="tabs-strip" role="tablist" aria-label="Theme sections" @keydown="onTabKey">
          <button
            v-for="t in TABS"
            :id="`theme-tab-${t.id}`"
            :key="t.id"
            role="tab"
            type="button"
            class="tab-btn"
            :class="{ 'tab-btn-active': activeTab === t.id }"
            :aria-selected="activeTab === t.id"
            :aria-controls="`theme-panel-${t.id}`"
            :tabindex="activeTab === t.id ? 0 : -1"
            :data-test="`tab-${t.id}`"
            @click="activeTab = t.id"
          >
            <span class="tab-icon" aria-hidden="true">{{ t.icon }}</span>
            <span>{{ t.label }}</span>
            <span v-if="tabDirty(t)" class="tab-dot" title="Changed since last publish" />
          </button>
        </div>

        <div :id="`theme-panel-${activeTab}`" role="tabpanel" class="data-card panel">
          <!-- ===== Quick start ===== -->
          <template v-if="activeTab === 'start'">
            <h2 class="mb-1 text-lg font-semibold">Set up your look in three steps</h2>
            <p class="mb-4 text-sm opacity-70">
              Enough for most stores. Every tab fine-tunes one part of it afterwards.
            </p>

            <ol class="steps-list">
              <li>
                <h3 class="step-title"><span class="step-num">1</span> Who you are</h3>
                <div class="grid gap-3 sm:grid-cols-2">
                  <label class="form-control">
                    <span class="label-text mb-1 text-sm">Store name</span>
                    <input v-model="draft.brand.name" class="input input-bordered input-sm" maxlength="40" data-test="brand-name" />
                  </label>
                  <label class="form-control">
                    <span class="label-text mb-1 text-sm">Tagline</span>
                    <input v-model="draft.brand.tagline" class="input input-bordered input-sm" maxlength="120" placeholder="Shop smarter" />
                  </label>
                  <label class="form-control sm:col-span-2">
                    <span class="label-text mb-1 text-sm">Logo URL <span class="opacity-60">(https:// or /path - optional)</span></span>
                    <input v-model="draft.brand.logoUrl" class="input input-bordered input-sm" maxlength="500" placeholder="https://cdn.example.com/logo.svg" />
                  </label>
                </div>
              </li>

              <li>
                <h3 class="step-title"><span class="step-num">2</span> Your colour</h3>
                <p class="mb-2 text-sm opacity-70">Pick your brand colour, or pull it from your logo. We build a complete light and dark palette around it.</p>
                <div class="flex flex-wrap items-center gap-2">
                  <input v-model="brandColour" type="color" class="color-swatch" aria-label="Brand colour" />
                  <input class="input input-bordered input-sm w-28 font-mono" :value="brandColour" maxlength="7" aria-label="Brand colour hex" @change="isHex(($event.target as HTMLInputElement).value) && (brandColour = ($event.target as HTMLInputElement).value)" />
                  <button class="btn btn-primary btn-sm" type="button" data-test="gen-both" @click="generateFromBrand(true)">Build palette</button>
                  <label class="btn btn-outline btn-sm" :class="{ 'btn-disabled': imageBusy }">
                    <span v-if="imageBusy" class="loading loading-spinner loading-xs" aria-hidden="true" />
                    From an image…
                    <input type="file" accept="image/*" class="hidden" :disabled="imageBusy" @change="onImagePicked" />
                  </label>
                </div>
                <div v-if="imageColours.length" class="mt-2 flex flex-wrap items-center gap-2" data-test="image-colours">
                  <span class="text-xs opacity-70">Found in the image - click one to use it:</span>
                  <span v-for="c in imageColours" :key="c" class="img-colour">
                    <button type="button" class="img-colour-main" :style="{ background: c }" :title="`Use ${c} as brand colour`" :aria-label="`Use ${c} as brand colour`" @click="useImageColour(c)" />
                    <button type="button" class="img-colour-accent" :title="`Use ${c} as accent`" @click="useAsAccent(c)">accent</button>
                  </span>
                </div>
              </li>

              <li>
                <h3 class="step-title"><span class="step-num">3</span> Your style</h3>
                <p class="mb-2 text-sm opacity-70">A complete look: fonts, corners, shadows and (unless you built your own above) colours. Change anything later.</p>
                <div class="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-4" role="list">
                  <button v-for="p in THEME_PRESETS.slice(0, 8)" :key="p.id" type="button" role="listitem" class="preset-card preset-compact text-left"
                    :class="{ 'preset-active': draft.preset === p.id }" :aria-pressed="draft.preset === p.id" @click="applyPreset(p.id)">
                    <span class="flex h-6 overflow-hidden rounded border border-base-300" aria-hidden="true">
                      <span class="flex-1" :style="{ background: p.config.light.background }" />
                      <span class="flex-1" :style="{ background: p.config.light.primary }" />
                      <span class="flex-1" :style="{ background: p.config.light.accent }" />
                      <span class="flex-1" :style="{ background: p.config.dark.background }" />
                    </span>
                    <span class="mt-1 block text-xs font-semibold">{{ p.label }}</span>
                  </button>
                </div>
                <button class="btn btn-link btn-xs mt-1 px-0" type="button" @click="activeTab = 'looks'">See all {{ THEME_PRESETS.length }} looks and {{ STYLE_KITS.length }} design styles →</button>
              </li>
            </ol>

            <div class="mt-4 flex flex-wrap items-center gap-2 border-t border-base-300 pt-3">
              <span class="text-sm">Currently: <b>{{ activePresetLabel }}</b> colours · <b>{{ activeStyleLabel }}</b> style · <b>{{ draft.fonts.display }}</b> headings</span>
              <span class="flex-1" />
              <button class="btn btn-ghost btn-sm" type="button" @click="openStorefrontPreview">Preview on storefront ↗</button>
              <button class="btn btn-primary btn-sm" type="button" :disabled="!canSave" @click="requestPublish">Publish</button>
            </div>
          </template>

          <!-- ===== Looks ===== -->
          <template v-else-if="activeTab === 'looks'">
            <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div>
                <h2 class="text-lg font-semibold">Looks</h2>
                <p class="text-sm opacity-70">Complete looks set colours, fonts, borders and shadows at once. Design styles change how surfaces are drawn.</p>
              </div>
              <input v-model="lookFilter" type="search" class="input input-bordered input-sm w-56" placeholder="Filter: fashion, dark, glass…" aria-label="Filter looks and styles" />
            </div>

            <h3 class="mb-2 text-sm font-semibold uppercase tracking-wide opacity-70">Complete looks</h3>
            <div class="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3" role="list">
              <button v-for="p in filteredPresets" :key="p.id" type="button" role="listitem" class="preset-card text-left"
                :class="{ 'preset-active': draft.preset === p.id }" :aria-pressed="draft.preset === p.id" :data-test="`preset-${p.id}`" @click="applyPreset(p.id)">
                <span class="flex h-10 overflow-hidden rounded-md border border-base-300" aria-hidden="true">
                  <span class="flex-1" :style="{ background: p.config.light.background }" />
                  <span class="flex-1" :style="{ background: p.config.light.primary }" />
                  <span class="flex-1" :style="{ background: p.config.light.accent }" />
                  <span class="flex-1" :style="{ background: p.config.dark.background }" />
                  <span class="flex-1" :style="{ background: p.config.dark.primary }" />
                </span>
                <span class="mt-2 block text-sm font-semibold">{{ p.label }}</span>
                <span class="block text-xs opacity-70">{{ p.bestFor }}</span>
                <span class="mt-1 block text-[11px] opacity-60">{{ p.config.fonts.display }} · {{ p.config.border.radius }} corners · {{ p.config.shadow.style }} shadow</span>
              </button>
              <p v-if="!filteredPresets.length" class="text-sm opacity-60">No looks match "{{ lookFilter }}".</p>
            </div>

            <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
              <h3 class="flex items-center gap-2 text-sm font-semibold uppercase tracking-wide opacity-70">
                Design style
                <span v-if="sectionDirty.style" class="badge badge-warning badge-xs">Changed</span>
              </h3>
              <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('style')">Reset style</button>
            </div>
            <p class="mb-2 text-sm opacity-70">
              How cards, panels and buttons are drawn (glass, soft-extruded, flat, hard-edged…). Material styles draw their own card shadows.
            </p>
            <label class="mb-3 flex cursor-pointer items-center gap-2 text-sm">
              <input v-model="applyKitLook" type="checkbox" class="checkbox checkbox-sm" data-test="apply-kit" />
              Also apply the style's colours, fonts, corners, outlines and shadow
            </label>
            <div class="grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-3" role="list">
              <button v-if="!lookFilter" type="button" role="listitem" class="option-row" :class="{ 'chip-active': draft.style.id === 'default' }"
                :aria-pressed="draft.style.id === 'default'" data-test="style-default" @click="resetSection('style')">
                <span class="style-dot" style="background: var(--color-base-300)" aria-hidden="true" />
                <span class="text-sm font-medium">Built-in</span>
                <span class="text-xs opacity-60">Space-Age Atomic as shipped</span>
              </button>
              <button v-for="k in filteredKits" :key="k.id" type="button" role="listitem" class="option-row"
                :class="{ 'chip-active': draft.style.id === k.id }" :aria-pressed="draft.style.id === k.id" :title="k.bestFor" :data-test="`style-${k.id}`" @click="applyStyle(k.id)">
                <span class="style-dot" aria-hidden="true">
                  <span :style="{ background: k.light.background }" />
                  <span :style="{ background: k.light.primary }" />
                  <span :style="{ background: k.dark.background }" />
                  <span :style="{ background: k.dark.primary }" />
                </span>
                <span class="text-sm font-medium">{{ k.label }}</span>
                <span class="text-xs opacity-60">{{ k.about }}</span>
              </button>
              <p v-if="!filteredKits.length" class="text-sm opacity-60">No styles match "{{ lookFilter }}".</p>
            </div>
          </template>

          <!-- ===== Brand ===== -->
          <template v-else-if="activeTab === 'brand'">
            <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                Brand
                <span v-if="sectionDirty.brand" class="badge badge-warning badge-sm">Changed</span>
              </h2>
              <button class="btn btn-ghost btn-xs" type="button" :disabled="!sectionDirty.brand" @click="resetSection('brand')">Undo brand changes</button>
            </div>
            <div class="grid gap-3 sm:grid-cols-2">
              <label class="form-control">
                <span class="label-text mb-1 text-sm">Store name</span>
                <input v-model="draft.brand.name" class="input input-bordered input-sm" maxlength="40" />
                <span class="label-text-alt mt-1 opacity-60">Shown in the header, footer, page titles, emails you send from the console.</span>
              </label>
              <label class="form-control">
                <span class="label-text mb-1 text-sm">Tagline</span>
                <input v-model="draft.brand.tagline" class="input input-bordered input-sm" maxlength="120" />
                <span class="label-text-alt mt-1 opacity-60">Default browser-tab title: "{{ draft.brand.name || 'Store' }}{{ draft.brand.tagline ? ' — ' + draft.brand.tagline : '' }}".</span>
              </label>
              <label class="form-control sm:col-span-2">
                <span class="label-text mb-1 text-sm">Logo URL</span>
                <input v-model="draft.brand.logoUrl" class="input input-bordered input-sm" placeholder="https://cdn.example.com/logo.svg - leave empty for the built-in mark" maxlength="500" />
                <span class="label-text-alt mt-1 opacity-60">https:// or a /path on the storefront. Square or round artwork works best; it is also used as the browser-tab icon.</span>
              </label>
            </div>
            <div v-if="draft.brand.logoUrl" class="mt-3 flex flex-wrap items-center gap-4">
              <span class="text-sm opacity-70">Logo check:</span>
              <span class="logo-check" :style="{ background: draft.light.primary }"><img :src="draft.brand.logoUrl" alt="" /></span>
              <span class="logo-check" :style="{ background: draft.light.surface }"><img :src="draft.brand.logoUrl" alt="" /></span>
              <span class="logo-check" :style="{ background: draft.dark.background }"><img :src="draft.brand.logoUrl" alt="" /></span>
              <span class="text-xs opacity-60">on primary · on surface · on dark</span>
            </div>
            <div class="tools-box mt-4">
              <span class="label-text text-sm font-medium">Colours from your logo or a photo</span>
              <div class="flex flex-wrap items-center gap-2">
                <label class="btn btn-outline btn-xs" :class="{ 'btn-disabled': imageBusy }">
                  <span v-if="imageBusy" class="loading loading-spinner loading-xs" aria-hidden="true" />
                  Choose an image…
                  <input type="file" accept="image/*" class="hidden" :disabled="imageBusy" @change="onImagePicked" />
                </label>
                <span class="text-xs opacity-60">Read locally in your browser; nothing is uploaded.</span>
              </div>
              <div v-if="imageColours.length" class="flex flex-wrap items-center gap-2">
                <span v-for="c in imageColours" :key="c" class="img-colour">
                  <button type="button" class="img-colour-main" :style="{ background: c }" :title="`Build palettes from ${c}`" :aria-label="`Build palettes from ${c}`" @click="useImageColour(c)" />
                  <button type="button" class="img-colour-accent" @click="useAsAccent(c)">accent</button>
                </span>
              </div>
            </div>
          </template>

          <!-- ===== Colours ===== -->
          <template v-else-if="activeTab === 'colours'">
            <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                Colours
                <span v-if="sectionDirty.colours" class="badge badge-warning badge-sm">Changed</span>
              </h2>
              <div role="tablist" class="tabs tabs-box tabs-sm">
                <button v-for="m in ['light', 'dark'] as const" :key="m" role="tab" type="button" class="tab" :class="{ 'tab-active': mode === m }" :aria-selected="mode === m" @click="mode = m">
                  {{ m === 'light' ? 'Light mode' : 'Dark mode' }}
                </button>
              </div>
            </div>

            <div class="mb-3 flex flex-wrap gap-2" role="list" aria-label="Colour themes">
              <button v-for="p in THEME_PRESETS" :key="p.id" type="button" role="listitem" class="colour-chip" :class="{ 'chip-active': draft.preset === p.id }" :aria-pressed="draft.preset === p.id" :title="p.label" @click="applyColourTheme(p.id)">
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
                <input type="color" class="color-swatch" :value="draft[mode][k]" :aria-label="`${PALETTE_LABELS[k].label} colour`" @input="setColor(k, ($event.target as HTMLInputElement).value)" />
                <div class="min-w-0 flex-1">
                  <div class="text-sm font-medium">{{ PALETTE_LABELS[k].label }}</div>
                  <div class="truncate text-xs opacity-60">{{ PALETTE_LABELS[k].hint }}</div>
                </div>
                <input class="input input-bordered input-xs w-24 font-mono" :value="draft[mode][k]" maxlength="7" spellcheck="false" :aria-label="`${PALETTE_LABELS[k].label} hex`" @change="setColor(k, ($event.target as HTMLInputElement).value)" />
              </div>
            </div>

            <div class="tools-box">
              <span class="label-text text-sm font-medium">Colour tools</span>
              <div class="flex flex-wrap items-center gap-2">
                <input v-model="brandColour" type="color" class="color-swatch" aria-label="Brand colour" />
                <button class="btn btn-outline btn-xs" type="button" data-test="gen-mode" @click="generateFromBrand(false)">Generate {{ mode }} palette from this colour</button>
                <button class="btn btn-outline btn-xs" type="button" @click="generateFromBrand(true)">Generate both</button>
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
              <li v-for="c in checks" :key="c.label" class="flex items-center justify-between rounded px-2 py-1" :class="c.ok ? 'bg-success/10' : 'bg-warning/20'">
                <span>{{ c.label }}</span>
                <span class="font-mono">{{ c.ratio.toFixed(1) }}:1 {{ c.ok ? '✓' : `(needs ${c.min})` }}</span>
              </li>
            </ul>
            <p v-if="failing.length" class="mt-2 flex flex-wrap items-center gap-2 text-xs text-warning" role="status">
              {{ failing.length }} pairing{{ failing.length > 1 ? 's' : '' }} below accessibility guidance in {{ mode }} mode.
              <button class="btn btn-warning btn-xs" type="button" @click="fixContrast">Fix automatically</button>
            </p>
          </template>

          <!-- ===== Typography ===== -->
          <template v-else-if="activeTab === 'type'">
            <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                Typography
                <span v-if="sectionDirty.fonts" class="badge badge-warning badge-sm">Changed</span>
              </h2>
              <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('fonts')">Reset fonts</button>
            </div>
            <p class="mb-3 text-sm opacity-70">Heading and body typefaces for the storefront and this console. Tested pairings first; mix your own below.</p>
            <div class="mb-4 grid gap-2 sm:grid-cols-2" role="list">
              <button v-for="fp in FONT_PAIRS" :key="fp.id" type="button" role="listitem" class="font-pair"
                :class="{ 'chip-active': draft.fonts.display === fp.display && draft.fonts.body === fp.body }"
                :aria-pressed="draft.fonts.display === fp.display && draft.fonts.body === fp.body" @click="applyFontPair(fp.id)">
                <span class="font-pair-sample" :style="{ fontFamily: `'${fp.display}', system-ui, sans-serif` }">Fresh finds</span>
                <span class="font-pair-body" :style="{ fontFamily: `'${fp.body}', system-ui, sans-serif` }">Everyday essentials, honest prices.</span>
                <span class="text-xs font-medium">{{ fp.label }}</span>
                <span class="text-[11px] opacity-60">{{ fp.vibe }}</span>
              </button>
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
            <p class="mt-2 text-xs opacity-60">Fonts other than "system-ui" load from Google Fonts on first visit (one small stylesheet request).</p>
          </template>

          <!-- ===== Shape ===== -->
          <template v-else-if="activeTab === 'shape'">
            <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                Borders
                <span v-if="sectionDirty.borders" class="badge badge-warning badge-sm">Changed</span>
              </h2>
              <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('borders')">Reset borders</button>
            </div>
            <p class="mb-3 text-sm opacity-70">Corner roundness and outline thickness. Pills and circles stay round.</p>
            <div class="mb-5 grid gap-4 sm:grid-cols-2">
              <div>
                <span class="label-text mb-1 block text-sm">Corners</span>
                <div class="flex flex-col gap-2">
                  <button v-for="r in RADIUS_KINDS" :key="r.value" type="button" class="option-row" :class="{ 'chip-active': draft.border.radius === r.value }" :aria-pressed="draft.border.radius === r.value" :data-test="`radius-${r.value}`" @click="setRadius(r.value)">
                    <span class="option-swatch" :style="{ borderRadius: r.value === 'sharp' ? '2px' : r.value === 'round' ? '14px' : '8px' }" aria-hidden="true" />
                    <span class="text-sm font-medium">{{ r.label }}</span>
                    <span class="text-xs opacity-60">{{ r.hint }}</span>
                  </button>
                </div>
              </div>
              <div>
                <span class="label-text mb-1 block text-sm">Outline thickness</span>
                <div class="flex flex-col gap-2">
                  <button v-for="w in BORDER_WIDTHS" :key="w.value" type="button" class="option-row" :class="{ 'chip-active': draft.border.width === w.value }" :aria-pressed="draft.border.width === w.value" :data-test="`width-${w.value}`" @click="setBorderWidth(w.value)">
                    <span class="option-swatch" :style="{ borderWidth: w.value === 'thin' ? '1px' : w.value === 'bold' ? '3px' : '2px' }" aria-hidden="true" />
                    <span class="text-sm font-medium">{{ w.label }}</span>
                    <span class="text-xs opacity-60">{{ w.hint }}</span>
                  </button>
                </div>
              </div>
            </div>

            <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                Shadows
                <span v-if="sectionDirty.shadows" class="badge badge-warning badge-sm">Changed</span>
              </h2>
              <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('shadows')">Reset shadows</button>
            </div>
            <p class="mb-3 text-sm opacity-70">
              Elevation of cards, buttons and menus.
              <span v-if="styleOwnsShadows" class="text-warning">
                The "{{ activeStyleLabel }}" design style draws its own card shadows, so this only affects buttons.
              </span>
            </p>
            <div class="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
              <button v-for="sh in SHADOW_STYLES" :key="sh.value" type="button" class="option-row" :class="{ 'chip-active': draft.shadow.style === sh.value }" :aria-pressed="draft.shadow.style === sh.value" :data-test="`shadow-${sh.value}`" @click="setShadow(sh.value)">
                <span class="shadow-swatch" :class="`sw-${sh.value}`" aria-hidden="true" />
                <span class="text-sm font-medium">{{ sh.label }}</span>
                <span class="text-xs opacity-60">{{ sh.hint }}</span>
              </button>
            </div>
          </template>

          <!-- ===== Comfort ===== -->
          <template v-else-if="activeTab === 'comfort'">
            <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                Comfort options
                <span v-if="sectionDirty.options" class="badge badge-warning badge-sm">Changed</span>
              </h2>
              <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('options')">Reset options</button>
            </div>
            <p class="mb-3 text-sm opacity-70">Global text size and motion, applied to the storefront and this console. Visitors who set "reduce motion" in their OS are already respected.</p>
            <div class="grid gap-4 sm:grid-cols-2">
              <div>
                <span class="label-text mb-1 block text-sm">Text size</span>
                <div class="flex flex-col gap-2">
                  <button v-for="t in TEXT_SCALES" :key="t.value" type="button" class="option-row" :class="{ 'chip-active': draft.options.textScale === t.value }" :aria-pressed="draft.options.textScale === t.value" :data-test="`scale-${t.value}`" @click="setTextScale(t.value)">
                    <span class="option-letter" :style="{ fontSize: t.value === 'small' ? '0.8rem' : t.value === 'large' ? '1.25rem' : '1rem' }" aria-hidden="true">Aa</span>
                    <span class="text-sm font-medium">{{ t.label }}</span>
                    <span class="text-xs opacity-60">{{ t.hint }}</span>
                  </button>
                </div>
              </div>
              <div>
                <span class="label-text mb-1 block text-sm">Motion</span>
                <div class="flex flex-col gap-2">
                  <button v-for="m in MOTION_LEVELS" :key="m.value" type="button" class="option-row" :class="{ 'chip-active': draft.options.motion === m.value }" :aria-pressed="draft.options.motion === m.value" :data-test="`motion-${m.value}`" @click="setMotion(m.value)">
                    <span class="option-letter" aria-hidden="true">{{ m.value === 'full' ? '≋' : m.value === 'reduced' ? '≈' : '═' }}</span>
                    <span class="text-sm font-medium">{{ m.label }}</span>
                    <span class="text-xs opacity-60">{{ m.hint }}</span>
                  </button>
                </div>
              </div>
            </div>
          </template>

          <!-- ===== Advanced ===== -->
          <template v-else>
            <h2 class="mb-1 flex items-center gap-2 text-lg font-semibold">
              Advanced
              <span v-if="sectionDirty.css" class="badge badge-warning badge-sm">CSS changed</span>
            </h2>

            <h3 class="mt-3 mb-1 text-sm font-semibold">Saved drafts <span class="opacity-60">(this browser only)</span></h3>
            <p class="mb-2 text-xs opacity-70">Park a direction, try another, come back. Drafts are not shared and are not published.</p>
            <div class="mb-2 flex flex-wrap gap-2">
              <input v-model="draftName" class="input input-bordered input-sm w-64" maxlength="60" placeholder="Name this draft (optional)" aria-label="Draft name" @keydown.enter.prevent="saveDraft" />
              <button class="btn btn-outline btn-sm" type="button" data-test="save-draft" @click="saveDraft">Save current as draft</button>
            </div>
            <ul v-if="draftsStore.drafts.value.length" class="mb-4 divide-y divide-base-300 rounded-box border border-base-300" data-test="drafts">
              <li v-for="d in draftsStore.drafts.value" :key="d.id" class="flex flex-wrap items-center gap-2 px-3 py-2">
                <span class="flex h-5 w-14 overflow-hidden rounded" aria-hidden="true">
                  <span class="flex-1" :style="{ background: d.config.light.background }" />
                  <span class="flex-1" :style="{ background: d.config.light.primary }" />
                  <span class="flex-1" :style="{ background: d.config.light.accent }" />
                  <span class="flex-1" :style="{ background: d.config.dark.background }" />
                </span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm font-medium">{{ d.name }}</span>
                  <span class="block text-xs opacity-60">{{ new Date(d.savedAt).toLocaleString() }} · {{ d.config.fonts.display }} · {{ STYLE_KITS.find(k => k.id === d.config.style.id)?.label ?? 'Built-in style' }}</span>
                </span>
                <button class="btn btn-primary btn-xs" type="button" @click="loadDraft(d.id)">Load</button>
                <button class="btn btn-ghost btn-xs" type="button" @click="renameDraft(d.id, d.name)">Rename</button>
                <button class="btn btn-ghost btn-xs text-error" type="button" aria-label="Delete draft" @click="removeDraft(d.id)">✕</button>
              </li>
            </ul>
            <p v-else class="mb-4 text-xs opacity-60">No saved drafts yet.</p>

            <h3 class="mb-1 text-sm font-semibold">Share a preview</h3>
            <p class="mb-2 text-xs opacity-70">Opens the real storefront with this draft applied for that tab only. Nothing is published; anyone with the link sees the draft, so share it with people you trust.</p>
            <div class="mb-4 flex flex-wrap gap-2">
              <button class="btn btn-outline btn-sm" type="button" @click="openStorefrontPreview">Open preview ↗</button>
              <button class="btn btn-ghost btn-sm" type="button" @click="copyText(storefrontPreviewUrl, 'Preview link')">Copy link</button>
              <span class="self-center text-xs opacity-60">→ {{ STOREFRONT_ORIGIN }}</span>
            </div>

            <h3 class="mb-1 text-sm font-semibold">Export</h3>
            <p class="mb-2 text-xs opacity-70">The exact CSS the apps inject for this config, for designers or a static site. Config JSON moves a theme between environments.</p>
            <div class="mb-4 flex flex-wrap gap-2">
              <button class="btn btn-ghost btn-sm" type="button" @click="download(`${slug}-storefront.css`, buildThemeCss(draft, 'storefront'), 'text/css')">Storefront CSS</button>
              <button class="btn btn-ghost btn-sm" type="button" @click="download(`${slug}-admin.css`, buildThemeCss(draft, 'admin'), 'text/css')">Admin CSS</button>
              <button class="btn btn-ghost btn-sm" type="button" @click="download(`${slug}-theme.json`, JSON.stringify(draft, null, 2), 'application/json')">Config JSON</button>
            </div>

            <h3 class="mb-1 flex items-center justify-between text-sm font-semibold">
              <span>Custom CSS</span>
              <button class="btn btn-ghost btn-xs" type="button" :disabled="!draft.customCss" @click="resetSection('css')">Clear</button>
            </h3>
            <p class="mb-2 text-xs opacity-70">
              Applied on the storefront and this console after the generated theme. No <code>@import</code>, <code>url(...)</code> or markup; max 4000 characters.
            </p>
            <textarea v-model="draft.customCss" class="textarea textarea-bordered h-32 w-full font-mono text-xs" spellcheck="false" placeholder="/* e.g. .font-display { letter-spacing: 0.04em; } */" data-test="custom-css" aria-label="Custom CSS" @input="touch" />
            <p v-if="cssProblem" class="mt-1 text-xs text-error" role="alert">{{ cssProblem }}</p>
            <p class="mt-1 text-right text-[11px] opacity-50">{{ draft.customCss.length }} / 4000</p>

            <details class="mt-3">
              <summary class="cursor-pointer text-sm font-medium">Config as JSON (paste from another environment)</summary>
              <div class="mt-2 flex flex-col gap-2">
                <textarea v-model="jsonText" class="textarea textarea-bordered h-40 font-mono text-xs" spellcheck="false" aria-label="Theme config JSON" />
                <p v-if="jsonError" class="text-xs text-error" role="alert">{{ jsonError }}</p>
                <div class="flex flex-wrap gap-2">
                  <button class="btn btn-ghost btn-xs" type="button" @click="exportJson">Load current config</button>
                  <button class="btn btn-ghost btn-xs" type="button" :disabled="!jsonText" @click="copyText(jsonText, 'JSON')">Copy</button>
                  <button class="btn btn-outline btn-xs" type="button" :disabled="!jsonText" @click="importJson">Apply JSON to editor</button>
                </div>
              </div>
            </details>
          </template>
        </div>
      </div>

      <!-- ===================== Preview ===================== -->
      <aside class="preview-aside" aria-label="Preview">
        <div class="data-card">
          <div class="mb-2 flex items-center justify-between gap-2">
            <h2 class="text-sm font-semibold">Preview</h2>
            <button class="btn btn-ghost btn-xs" type="button" :aria-pressed="previewWide" :title="previewWide ? 'Dock preview to the side' : 'Give the preview the full width'" @click="previewWide = !previewWide">
              {{ previewWide ? '⇥ Dock' : '⇔ Wide' }}
            </button>
          </div>
          <ThemePreview :config="draft" :published="published" :mode="mode" :dirty="dirty" />
        </div>
      </aside>
    </div>

    <!-- Publish confirmation -->
    <dialog ref="publishDialog" class="modal" aria-labelledby="publish-title">
      <div class="modal-box">
        <h3 id="publish-title" class="text-lg font-bold">Publish this theme?</h3>
        <p class="py-2 text-sm opacity-80">Everyone sees it on their next page load: every storefront visitor and every console user.</p>
        <ul v-if="changedSections.length" class="mb-2 list-disc pl-5 text-sm">
          <li v-for="s in changedSections" :key="s">{{ s }}</li>
        </ul>
        <p v-if="failingBoth" class="alert alert-warning py-2 text-sm">
          {{ failingBoth }} colour pairing{{ failingBoth > 1 ? 's are' : ' is' }} below accessibility guidance. Some text may be hard to read.
        </p>
        <div class="modal-action">
          <button class="btn btn-ghost btn-sm" type="button" @click="publishDialog?.close()">Keep editing</button>
          <button class="btn btn-ghost btn-sm" type="button" @click="publishDialog?.close(); openStorefrontPreview()">Check on storefront first</button>
          <button class="btn btn-primary btn-sm" type="button" data-test="confirm-publish" @click="publish">Publish now</button>
        </div>
      </div>
      <form method="dialog" class="modal-backdrop"><button type="submit">close</button></form>
    </dialog>
  </section>
</template>

<style scoped>
.theme-editor {
  max-width: 90rem;
}
.editor-grid {
  display: grid;
  gap: 1rem;
}
@media (min-width: 1024px) {
  .editor-grid {
    grid-template-columns: minmax(0, 1fr) 24rem;
  }
  .editor-grid-wide {
    grid-template-columns: 1fr;
  }
  .editor-grid-wide .preview-aside {
    order: -1;
  }
  .editor-grid:not(.editor-grid-wide) .preview-aside {
    position: sticky;
    top: 4rem;
    align-self: start;
    max-height: calc(100vh - 5rem);
    overflow: auto;
  }
}
@media (min-width: 1536px) {
  .editor-grid:not(.editor-grid-wide) {
    grid-template-columns: minmax(0, 1fr) 28rem;
  }
}
.preview-aside {
  min-width: 0;
}

.action-bar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  padding: 0.5rem 0.75rem;
  background: var(--color-base-100);
  border: var(--cw-bw, 2px) solid var(--color-base-300);
  border-radius: var(--radius-field);
}

.tabs-strip {
  display: flex;
  gap: 0.25rem;
  overflow-x: auto;
  padding-bottom: 0.25rem;
  margin-bottom: -2px;
  scrollbar-width: thin;
}
.tab-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  white-space: nowrap;
  padding: 0.45rem 0.8rem;
  font-size: 0.85rem;
  font-weight: 600;
  border: 2px solid transparent;
  border-bottom: 0;
  border-radius: var(--radius-field) var(--radius-field) 0 0;
  color: var(--color-base-content);
  opacity: 0.75;
}
.tab-btn:hover,
.tab-btn:focus-visible {
  opacity: 1;
  background: var(--color-base-100);
}
.tab-btn-active {
  opacity: 1;
  background: var(--color-base-100);
  border-color: var(--cw-outline, var(--color-base-300));
}
.tab-icon {
  font-size: 0.8rem;
  opacity: 0.7;
}
.tab-dot {
  width: 0.45rem;
  height: 0.45rem;
  border-radius: 9999px;
  background: var(--color-warning);
}
.panel {
  border-top-left-radius: 0;
}

.steps-list {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  padding: 0;
  margin: 0;
  list-style: none;
}
.step-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
  font-size: 1rem;
  font-weight: 700;
}
.step-num {
  display: grid;
  place-items: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 9999px;
  background: var(--color-primary);
  color: var(--color-primary-content);
  font-size: 0.8rem;
}

.preset-card {
  border: 2px solid var(--color-base-300);
  border-radius: var(--radius-field);
  padding: 0.6rem;
  background: var(--color-base-100);
  transition: border-color 150ms ease;
}
.preset-compact {
  padding: 0.45rem;
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
.font-pair {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.1rem;
  padding: 0.6rem 0.75rem;
  text-align: left;
  border: 2px solid var(--color-base-300);
  border-radius: var(--radius-field);
  background: var(--color-base-100);
}
.font-pair:hover,
.font-pair:focus-visible {
  border-color: var(--color-primary);
}
.font-pair-sample {
  font-size: 1.35rem;
  font-weight: 700;
  line-height: 1.1;
}
.font-pair-body {
  font-size: 0.85rem;
  opacity: 0.8;
  margin-bottom: 0.25rem;
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
.color-swatch {
  width: 2.25rem;
  height: 2.25rem;
  padding: 0;
  border: 2px solid var(--color-base-300);
  border-radius: 0.5rem;
  background: none;
  cursor: pointer;
}
.img-colour {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 0.15rem;
}
.img-colour-main {
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 0.5rem;
  border: 2px solid var(--color-base-300);
}
.img-colour-main:hover,
.img-colour-main:focus-visible {
  border-color: var(--color-primary);
}
.img-colour-accent {
  font-size: 0.65rem;
  text-decoration: underline;
  opacity: 0.7;
}
.logo-check {
  display: grid;
  place-items: center;
  width: 3rem;
  height: 3rem;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-300);
}
.logo-check img {
  width: 2rem;
  height: 2rem;
  object-fit: contain;
}
</style>
