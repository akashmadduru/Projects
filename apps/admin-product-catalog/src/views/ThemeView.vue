<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import ChoiceGroup from '@/components/theme/ChoiceGroup.vue';
import ThemePreview from '@/components/theme/ThemePreview.vue';
import { paintTheme, setPublished, useBranding } from '@/composables/useBranding';
import { useThemeDrafts } from '@/composables/useThemeDrafts';
import { errorInfo } from '@/lib/adminApi';
import { extractColours } from '@/lib/imageColours';
import { ApiError } from '@/lib/problem';
import {
  BORDER_COLORS,
  BORDER_WIDTHS,
  BORDER_STYLES,
  BUTTON_FILLS,
  BUTTON_HOVERS,
  BUTTON_SHAPES,
  BUTTON_TEXTS,
  CARD_ACCENTS,
  CARD_FILLS,
  CARD_HOVERS,
  CARD_SHAPES,
  DEFAULT_THEME,
  FONT_CATALOG,
  FONT_CATEGORY_LABELS,
  FONT_OPTIONS,
  FONT_PAIRS,
  HARMONIES,
  HEADING_WEIGHTS,
  LINE_HEIGHTS,
  MOTION_LEVELS,
  PALETTE_KEYS,
  PREVIEW_HASH_KEY,
  RADIUS_KINDS,
  SHADOW_INTENSITIES,
  SHADOW_STYLES,
  SHADOW_TINTS,
  STYLE_FX,
  TEXT_SCALES,
  TRACKINGS,
  autoFixPalette,
  buildThemeCss,
  contrastChecks,
  customCssProblem,
  deriveDarkFromLight,
  encodeThemeForUrl,
  generatePalette,
  isHex,
  normalizeTheme,
  PREVIEW_BORDER_WIDTH,
  PREVIEW_CARD_RADIUS,
  PREVIEW_RADIUS,
  previewButtonStyle,
  previewCardBackground,
  previewOutline,
  previewShadows,
  randomPalette,
  type BorderColor,
  type FontCategory,
  type BorderStyle,
  type BorderWidth,
  type ButtonFill,
  type ButtonShape,
  type CardAccent,
  type CardFill,
  type CardShape,
  type ShadowIntensity,
  type ShadowTint,
  type Harmony,
  type MotionLevel,
  type PaletteKey,
  type RadiusKind,
  type ShadowStyle,
  type StyleId,
  type TextScale,
  type ThemeConfig,
  type ThemePalette,
} from '@/lib/themeEngine';
import { COLOUR_CATEGORIES, COLOUR_COMBOS, type ColourCategory } from '@/lib/themeColours';
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
type Section = 'style' | 'brand' | 'colours' | 'fonts' | 'borders' | 'shadows' | 'cards' | 'buttons' | 'options' | 'css';
type TabId = 'start' | 'looks' | 'brand' | 'colours' | 'type' | 'shape' | 'components' | 'comfort' | 'advanced';
/** Config groups edited field-by-field through ChoiceGroups. */
type Part = 'border' | 'shadow' | 'card' | 'button' | 'fonts';

interface Tab {
  id: TabId;
  label: string;
  icon: string;
  /** One line under the label in the section menu. */
  hint: string;
  group: string;
  sections: Section[];
}

const TABS: readonly Tab[] = [
  { id: 'start', label: 'Quick start', icon: '✦', hint: 'Name, colour and look in 3 steps', group: 'Start', sections: [] },
  { id: 'looks', label: 'Looks', icon: '◧', hint: 'Complete looks and design styles', group: 'Start', sections: ['style'] },
  { id: 'brand', label: 'Brand', icon: '♛', hint: 'Name, tagline and logo', group: 'Brand & colour', sections: ['brand'] },
  { id: 'colours', label: 'Colours', icon: '◐', hint: 'Light and dark palettes', group: 'Brand & colour', sections: ['colours'] },
  { id: 'type', label: 'Typography', icon: 'Aa', hint: 'Fonts, weight and spacing', group: 'Brand & colour', sections: ['fonts'] },
  { id: 'shape', label: 'Borders & shadows', icon: '▢', hint: 'Corners, outlines and depth', group: 'Shape', sections: ['borders', 'shadows'] },
  { id: 'components', label: 'Cards & buttons', icon: '▤', hint: 'Card fill, hover and buttons', group: 'Shape', sections: ['cards', 'buttons'] },
  { id: 'comfort', label: 'Comfort', icon: '☺', hint: 'Text size and motion', group: 'More', sections: ['options'] },
  { id: 'advanced', label: 'Advanced', icon: '</>', hint: 'Drafts, export and custom CSS', group: 'More', sections: ['css'] },
];

/** TABS grouped for the section menu, in order. */
const TAB_GROUPS: readonly { name: string; tabs: Tab[] }[] = TABS.reduce<{ name: string; tabs: Tab[] }[]>((acc, t) => {
  const last = acc[acc.length - 1];
  if (last && last.name === t.group) last.tabs.push(t);
  else acc.push({ name: t.group, tabs: [t] });
  return acc;
}, []);

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
    cards: diff(d.card, base.card),
    buttons: diff(d.button, base.button),
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
  cards: 'Cards',
  buttons: 'Buttons',
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
  notify(`Applied the "${p.label}" look: colours, fonts, borders, shadows, cards and buttons.`, true);
}

function applyColourTheme(id: string): void {
  const p = THEME_PRESETS.find((x) => x.id === id);
  if (!p) return;
  draft.value = clone({ ...draft.value, preset: id, light: p.config.light, dark: p.config.dark });
  notify(`Applied the "${p.label}" colours.`, true);
}

/**
 * Choose a design language. Its runtime effects always switch on; the starter kit (palette, fonts,
 * corners, outline, shadow) is applied too unless the admin wants to keep their own look.
 */
function applyStyle(id: StyleId): void {
  const kit = STYLE_KITS.find((k) => k.id === id);
  if (!kit) return;
  draft.value = styleConfig(id);
  notify(`Applied the "${kit.label}" design style.`, true);
}

/** The draft with a design style (and, unless unticked, its starter kit) applied. */
function styleConfig(id: StyleId): ThemeConfig {
  const kit = STYLE_KITS.find((k) => k.id === id);
  const next: ThemeConfig = { ...draft.value, style: { id } };
  if (kit && applyKitLook.value) {
    next.preset = 'custom';
    next.light = { ...kit.light };
    next.dark = { ...kit.dark };
    next.fonts = { ...draft.value.fonts, ...kit.fonts };
    next.border = { ...draft.value.border, ...kit.border };
    next.shadow = { ...draft.value.shadow, style: kit.shadow };
  }
  return next;
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
  notify('Palette generated from your brand colour. Tweak any swatch in Colours.', true);
}

/** Colour-only suggestion: swaps both palettes, leaves fonts/borders/shadows alone. */
function applyCombo(id: string): void {
  const c = COLOUR_COMBOS.find((x) => x.id === id);
  if (!c) return;
  draft.value = { ...draft.value, preset: 'custom', light: { ...c.light }, dark: { ...c.dark } };
  brandColour.value = c.primary;
  notify(`Applied ${c.label}. Fonts and shapes are unchanged.`, true);
}

/** Palettes for every colour-wheel harmony of the current brand colour (shown as suggestions). */
const harmonySuggestions = computed(() =>
  isHex(brandColour.value)
    ? HARMONIES.map((h) => ({
        ...h,
        light: generatePalette(brandColour.value, 'light', { harmony: h.value }),
        dark: generatePalette(brandColour.value, 'dark', { harmony: h.value }),
      }))
    : [],
);

function applyHarmony(h: Harmony): void {
  const s = harmonySuggestions.value.find((x) => x.value === h);
  if (!s) return;
  draft.value = { ...draft.value, preset: 'custom', light: { ...s.light }, dark: { ...s.dark } };
  notify(`Applied the ${s.label.toLowerCase()} harmony.`, true);
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
  notify('Random palette applied. Roll again or undo.', true);
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
  if (p) draft.value = { ...draft.value, preset: 'custom', fonts: { ...draft.value.fonts, display: p.display, body: p.body } };
}

/** Change one field of a config group, keeping the rest of the group. */
function setPart<K extends Part>(part: K, patch: Partial<ThemeConfig[K]>): void {
  draft.value = { ...draft.value, [part]: { ...draft.value[part], ...patch } };
}

function setRadius(radius: RadiusKind): void {
  setPart('border', { radius });
}

function setBorderWidth(width: BorderWidth): void {
  setPart('border', { width });
}

function setShadow(style: ShadowStyle): void {
  setPart('shadow', { style });
}

const fontFilter = ref('');
const filteredFontPairs = computed(() => {
  const q = fontFilter.value.trim().toLowerCase();
  return q ? FONT_PAIRS.filter((p) => `${p.label} ${p.vibe}`.toLowerCase().includes(q)) : FONT_PAIRS;
});
/** Font dropdowns: one optgroup per category, plus the no-download system stack. */
const fontGroups = [
  ...(Object.keys(FONT_CATALOG) as FontCategory[]).map((c) => ({ label: `${FONT_CATEGORY_LABELS[c]} (${FONT_CATALOG[c].length})`, fonts: FONT_CATALOG[c] })),
  { label: 'No download', fonts: ['system-ui'] as readonly string[] },
];

// ---- colour combination browser --------------------------------------------------------------

const comboCategory = ref<ColourCategory | 'all'>('all');
const comboFilter = ref('');
const comboCounts = computed(() => {
  const out = new Map<string, number>();
  for (const c of COLOUR_COMBOS) out.set(c.category, (out.get(c.category) ?? 0) + 1);
  return out;
});
const filteredCombos = computed(() => {
  const q = comboFilter.value.trim().toLowerCase();
  return COLOUR_COMBOS.filter(
    (c) => (comboCategory.value === 'all' || c.category === comboCategory.value) && (!q || `${c.label} ${c.mood} ${c.category}`.toLowerCase().includes(q)),
  );
});

// Small visual samples for ChoiceGroup options.
/** The draft with one group patched: what an option would look like if picked. */
function withPart<K extends Part>(part: K, patch: Partial<ThemeConfig[K]>): ThemeConfig {
  return { ...draft.value, [part]: { ...draft.value[part], ...patch } };
}

/** Small visual samples for ChoiceGroup options, all drawn by the engine's preview helpers. */
const swatch = {
  radius: (v: RadiusKind) => ({ borderRadius: PREVIEW_RADIUS[v], borderWidth: '2px', borderColor: 'currentColor' }),
  width: (v: BorderWidth) => ({ borderWidth: PREVIEW_BORDER_WIDTH[v], borderColor: 'currentColor', background: 'var(--color-base-200)' }),
  borderColour: (v: BorderColor) => ({ borderWidth: '3px', borderColor: previewOutline(withPart('border', { color: v }), mode.value) }),
  borderStyle: (v: BorderStyle) => ({ borderWidth: '3px', borderStyle: v, borderColor: 'currentColor' }),
  shadow: (v: ShadowStyle) => ({ boxShadow: v === 'none' ? 'none' : previewShadows(withPart('shadow', { style: v }), mode.value).card }),
  intensity: (v: ShadowIntensity) => {
    const style = draft.value.shadow.style === 'none' ? 'soft' : draft.value.shadow.style;
    return { boxShadow: previewShadows(withPart('shadow', { style, intensity: v }), mode.value).card };
  },
  tint: (v: ShadowTint) => {
    const style = draft.value.shadow.style === 'none' ? 'soft' : draft.value.shadow.style;
    return { boxShadow: previewShadows(withPart('shadow', { style, tint: v, intensity: 'strong' }), mode.value).card };
  },
  cardFill: (v: CardFill) => ({ background: previewCardBackground(withPart('card', { fill: v }), mode.value), borderColor: draft.value[mode.value].text }),
  cardShape: (v: CardShape) => ({ borderRadius: PREVIEW_CARD_RADIUS[v] ?? PREVIEW_RADIUS[draft.value.border.radius], borderWidth: '2px', borderColor: 'currentColor' }),
  cardAccent: (v: CardAccent) => ({ background: previewCardBackground(withPart('card', { accent: v }), mode.value), borderColor: draft.value[mode.value].text }),
  buttonShape: (v: ButtonShape) => ({ ...previewButtonStyle(withPart('button', { shape: v }), mode.value), height: '1rem', alignSelf: 'center' }),
  buttonFill: (v: ButtonFill) => ({ ...previewButtonStyle(withPart('button', { fill: v }), mode.value), height: '1rem', alignSelf: 'center' }),
};

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
    case 'cards':
      draft.value = { ...cur, card: d.card };
      break;
    case 'buttons':
      draft.value = { ...cur, button: d.button };
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

function commitSnapshot(): void {
  snapshotTimer = undefined;
  const snap = JSON.stringify(draft.value);
  if (snap === history.value[cursor.value]) return;
  history.value = [...history.value.slice(0, cursor.value + 1), snap].slice(-60);
  cursor.value = history.value.length - 1;
}

function recordSnapshot(): void {
  if (restoring) return;
  clearTimeout(snapshotTimer);
  // Debounced so dragging a colour picker is one undo step, not hundreds.
  snapshotTimer = setTimeout(commitSnapshot, 350);
}

/** An edit made within the debounce window must become its own step before undo/redo moves. */
function flushSnapshot(): void {
  if (snapshotTimer === undefined) return;
  clearTimeout(snapshotTimer);
  commitSnapshot();
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

function undo(): void {
  flushSnapshot();
  goTo(cursor.value - 1);
}
function redo(): void {
  flushSnapshot();
  goTo(cursor.value + 1);
}

// ---- toast notices ---------------------------------------------------------------------------

/** Whether the current notice offers "Undo" (one-click changes that replace a lot at once). */
const noticeUndo = ref(false);
let noticeTimer: ReturnType<typeof setTimeout> | undefined;

function notify(message: string, undoable = false): void {
  notice.value = message;
  noticeUndo.value = undoable;
}

function undoFromNotice(): void {
  undo();
  notice.value = '';
}

// Notices are toasts: they never shift the layout and fade on their own (longer when undoable).
watch(notice, (msg) => {
  clearTimeout(noticeTimer);
  if (!msg) {
    noticeUndo.value = false;
    return;
  }
  noticeTimer = setTimeout(() => {
    notice.value = '';
  }, noticeUndo.value ? 8000 : 5000);
});

// ---- try before you apply ----------------------------------------------------------------------

/**
 * Hovering or focusing a one-click option shows it in the preview pane only (the console and the
 * draft are untouched); clicking applies it. A short grace period stops the preview flickering back
 * to the draft while the pointer moves between neighbouring options.
 */
const trial = ref<{ label: string; config: ThemeConfig } | null>(null);
let trialTimer: ReturnType<typeof setTimeout> | undefined;
const previewConfig = computed(() => trial.value?.config ?? draft.value);

function tryOn(label: string, patch: Partial<ThemeConfig>): void {
  clearTimeout(trialTimer);
  trial.value = { label, config: { ...draft.value, ...patch } };
}

function endTry(): void {
  clearTimeout(trialTimer);
  trialTimer = setTimeout(() => {
    trial.value = null;
  }, 140);
}

function previewPart<K extends Part>(part: K, patch: Partial<ThemeConfig[K]> | null, label = ''): void {
  if (!patch) {
    endTry();
    return;
  }
  tryOn(label, { [part]: { ...draft.value[part], ...patch } } as Partial<ThemeConfig>);
}

function tryPreset(id: string): void {
  const p = THEME_PRESETS.find((x) => x.id === id);
  if (p) tryOn(p.label, { ...p.config, brand: draft.value.brand });
}

function tryPalettes(label: string, light: ThemePalette, dark: ThemePalette): void {
  tryOn(label, { light, dark });
}

function tryStyle(id: StyleId): void {
  const kit = STYLE_KITS.find((k) => k.id === id);
  if (kit) tryOn(kit.label, styleConfig(id));
}

function tryFontPair(id: string): void {
  const p = FONT_PAIRS.find((x) => x.id === id);
  if (p) tryOn(p.label, { fonts: { ...draft.value.fonts, display: p.display, body: p.body } });
}

/** Inline styles for a look card's mini specimen: its own font, corners, outline, shadow and button. */
function specimen(cfg: ThemeConfig): Record<'card' | 'heading' | 'body' | 'button', Record<string, string>> {
  const p = cfg.light;
  return {
    card: {
      background: previewCardBackground(cfg, 'light'),
      color: p.text,
      border: `${PREVIEW_BORDER_WIDTH[cfg.border.width]} ${cfg.border.style} ${previewOutline(cfg, 'light')}`,
      borderRadius: PREVIEW_CARD_RADIUS[cfg.card.shape] ?? PREVIEW_RADIUS[cfg.border.radius],
      boxShadow: cfg.card.fill === 'outline' ? 'none' : previewShadows(cfg, 'light').card,
    },
    heading: { fontFamily: `'${cfg.fonts.display}', system-ui, sans-serif`, fontWeight: { default: '700', medium: '500', semibold: '600', bold: '700' }[cfg.fonts.headingWeight] },
    body: { fontFamily: `'${cfg.fonts.body}', system-ui, sans-serif`, opacity: '0.75' },
    button: previewButtonStyle(cfg, 'light'),
  };
}

// ---- guided flow -------------------------------------------------------------------------------

const tabIndex = computed(() => TABS.findIndex((t) => t.id === activeTab.value));
const prevTab = computed(() => TABS[tabIndex.value - 1] ?? null);
const nextTab = computed(() => TABS[tabIndex.value + 1] ?? null);

function goToTab(id: TabId): void {
  activeTab.value = id;
  // Coming from the footer of a long panel: bring the new panel's top back into view.
  void nextTick(() => {
    const panel = document.getElementById(`theme-panel-${id}`);
    if (panel && panel.getBoundingClientRect().top < 0) panel.scrollIntoView?.({ block: 'start' });
  });
}

// ---- font specimens ----------------------------------------------------------------------------

/**
 * Pairing and look cards render their sample text in each font, so those fonts must actually be
 * loaded (otherwise every sample silently falls back to system-ui). One Google Fonts request, limited
 * with `text=` to the glyphs the samples use, keeps it to a few KB.
 */
const SPECIMEN_TEXT = 'Fresh finds Everyday essentials, honest prices. Aa';
const SPECIMEN_LINK_ID = 'cw-theme-specimens';

function loadSpecimenFonts(): void {
  if (typeof document === 'undefined' || document.getElementById(SPECIMEN_LINK_ID)) return;
  const names = FONT_OPTIONS.filter((f) => f !== 'system-ui');
  const glyphs = encodeURIComponent([...new Set(SPECIMEN_TEXT + SPECIMEN_TEXT.toUpperCase())].join(''));
  // ~190 families: split into a few requests so no single URL gets unwieldy.
  for (let i = 0; i < names.length; i += 40) {
    const fam = names
      .slice(i, i + 40)
      .map((n) => `family=${encodeURIComponent(n).replace(/%20/g, '+')}:wght@400;700`)
      .join('&');
    const link = document.createElement('link');
    link.id = i === 0 ? SPECIMEN_LINK_ID : `${SPECIMEN_LINK_ID}-${i / 40}`;
    link.rel = 'stylesheet';
    link.href = `https://fonts.googleapis.com/css2?${fam}&text=${glyphs}&display=swap`;
    document.head.appendChild(link);
  }
}

watch(
  activeTab,
  (t) => {
    if (t === 'type' || t === 'looks' || t === 'start') loadSpecimenFonts();
  },
  { immediate: true },
);

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

/** Up/Down/Home/End move focus between section items (Enter/Space opens one); Escape closes the menu. */
function onNavKey(e: KeyboardEvent): void {
  if (e.key === 'Escape' && menuOpen.value) {
    e.preventDefault();
    closeMenu(true);
    return;
  }
  const items = Array.from((e.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('.rail-item'));
  const i = items.indexOf(document.activeElement as HTMLButtonElement);
  let next: number;
  if (e.key === 'ArrowDown') next = (i + 1) % items.length;
  else if (e.key === 'ArrowUp') next = (i - 1 + items.length) % items.length;
  else if (e.key === 'Home') next = 0;
  else if (e.key === 'End') next = items.length - 1;
  else return;
  e.preventDefault();
  items[next]?.focus();
}

// ---- compact section menu ----------------------------------------------------------------------

const menuOpen = ref(false);
const menuRoot = ref<HTMLElement | null>(null);
const dirtyTabCount = computed(() => TABS.filter((t) => tabDirty(t)).length);

function closeMenu(refocus = false): void {
  menuOpen.value = false;
  if (refocus) void nextTick(() => menuRoot.value?.querySelector<HTMLButtonElement>('.switcher-current')?.focus());
}

function onDocPointer(e: PointerEvent): void {
  if (menuOpen.value && menuRoot.value && !menuRoot.value.contains(e.target as Node)) closeMenu();
}

// Opening the menu puts focus on the current section, ready for the arrow keys.
watch(menuOpen, (open) => {
  if (open) void nextTick(() => menuRoot.value?.querySelector<HTMLButtonElement>('.switcher-menu .rail-item-active')?.focus());
});

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
    // Anything applied replaces a hover try-on: the preview now shows the real draft.
    clearTimeout(trialTimer);
    trial.value = null;
    recordSnapshot();
    if (!loading.value) draftsStore.writeWorking(dirty.value ? cfg : null);
  },
  { deep: true },
);

onMounted(() => {
  window.addEventListener('keydown', onKey);
  window.addEventListener('beforeunload', onBeforeUnload);
  document.addEventListener('pointerdown', onDocPointer);
  void load();
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey);
  window.removeEventListener('beforeunload', onBeforeUnload);
  document.removeEventListener('pointerdown', onDocPointer);
  clearTimeout(snapshotTimer);
  clearTimeout(noticeTimer);
  clearTimeout(trialTimer);
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
    <div class="theme-toast" role="status" aria-live="polite">
      <div v-if="notice" class="alert alert-success py-2 text-sm shadow-lg" data-test="notice">
        <span class="flex-1">{{ notice }}</span>
        <button v-if="noticeUndo" class="btn btn-sm" type="button" data-test="notice-undo" @click="undoFromNotice">Undo</button>
        <button class="btn btn-ghost btn-xs" type="button" aria-label="Dismiss" @click="notice = ''">✕</button>
      </div>
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
      <div class="editor-col min-w-0">
        <!--
          Section navigation adapts to the editor's own width (container query, see .nav-host):
          a grouped side menu when there is room, a one-line switcher with a drop-down otherwise.
        -->
        <div class="nav-host">
          <nav class="section-rail" aria-label="Theme sections" @keydown="onNavKey">
            <div v-for="g in TAB_GROUPS" :key="g.name" class="rail-group">
              <span class="rail-group-name">{{ g.name }}</span>
              <button
                v-for="t in g.tabs"
                :id="`theme-tab-${t.id}`"
                :key="t.id"
                type="button"
                class="rail-item"
                :class="{ 'rail-item-active': activeTab === t.id }"
                :aria-current="activeTab === t.id ? 'step' : undefined"
                :aria-controls="`theme-panel-${t.id}`"
                :data-test="`tab-${t.id}`"
                @click="goToTab(t.id)"
              >
                <span class="rail-icon" aria-hidden="true">{{ t.icon }}</span>
                <span class="rail-text">
                  <span class="rail-label">{{ t.label }}</span>
                  <span class="rail-hint">{{ t.hint }}</span>
                </span>
                <span v-if="tabDirty(t)" class="tab-dot" title="Changed since last publish" />
              </button>
            </div>
          </nav>

          <div class="section-switcher">
            <button class="btn btn-ghost btn-sm btn-square" type="button" :disabled="!prevTab" :aria-label="prevTab ? `Previous: ${prevTab.label}` : 'Previous section'" @click="prevTab && goToTab(prevTab.id)">←</button>
            <div ref="menuRoot" class="switcher-menu-root">
              <button
                class="switcher-current"
                type="button"
                aria-haspopup="true"
                :aria-expanded="menuOpen"
                aria-controls="theme-section-menu"
                data-test="section-menu"
                @click="menuOpen = !menuOpen"
              >
                <span class="rail-icon" aria-hidden="true">{{ TABS[tabIndex]!.icon }}</span>
                <span class="min-w-0 flex-1 truncate text-left">
                  <span class="font-semibold">{{ TABS[tabIndex]!.label }}</span>
                  <span class="opacity-60"> · {{ tabIndex + 1 }} of {{ TABS.length }}</span>
                </span>
                <span v-if="dirtyTabCount" class="badge badge-warning badge-xs" :title="`${dirtyTabCount} section(s) changed since last publish`">{{ dirtyTabCount }}</span>
                <span aria-hidden="true">▾</span>
              </button>
              <div v-if="menuOpen" id="theme-section-menu" class="switcher-menu" @keydown="onNavKey">
                <div v-for="g in TAB_GROUPS" :key="g.name" class="rail-group">
                  <span class="rail-group-name">{{ g.name }}</span>
                  <button
                    v-for="t in g.tabs"
                    :key="t.id"
                    type="button"
                    class="rail-item"
                    :class="{ 'rail-item-active': activeTab === t.id }"
                    :aria-current="activeTab === t.id ? 'step' : undefined"
                    :data-test="`menu-tab-${t.id}`"
                    @click="goToTab(t.id); menuOpen = false"
                  >
                    <span class="rail-icon" aria-hidden="true">{{ t.icon }}</span>
                    <span class="rail-text">
                      <span class="rail-label">{{ t.label }}</span>
                      <span class="rail-hint">{{ t.hint }}</span>
                    </span>
                    <span v-if="tabDirty(t)" class="tab-dot" title="Changed since last publish" />
                  </button>
                </div>
              </div>
            </div>
            <button class="btn btn-ghost btn-sm btn-square" type="button" :disabled="!nextTab" :aria-label="nextTab ? `Next: ${nextTab.label}` : 'Next section'" @click="nextTab && goToTab(nextTab.id)">→</button>
          </div>

        <section :id="`theme-panel-${activeTab}`" class="data-card panel" :aria-labelledby="`theme-tab-${activeTab}`">
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
                    :class="{ 'preset-active': draft.preset === p.id }" :aria-pressed="draft.preset === p.id" @click="applyPreset(p.id)" @mouseenter="tryPreset(p.id)" @focus="tryPreset(p.id)" @mouseleave="endTry" @blur="endTry">
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
                :class="{ 'preset-active': draft.preset === p.id }" :aria-pressed="draft.preset === p.id" :data-test="`preset-${p.id}`" @click="applyPreset(p.id)" @mouseenter="tryPreset(p.id)" @focus="tryPreset(p.id)" @mouseleave="endTry" @blur="endTry">
                <span class="flex h-10 overflow-hidden rounded-md border border-base-300" aria-hidden="true">
                  <span class="flex-1" :style="{ background: p.config.light.background }" />
                  <span class="flex-1" :style="{ background: p.config.light.primary }" />
                  <span class="flex-1" :style="{ background: p.config.light.accent }" />
                  <span class="flex-1" :style="{ background: p.config.dark.background }" />
                  <span class="flex-1" :style="{ background: p.config.dark.primary }" />
                </span>
                <span class="mt-2 block text-sm font-semibold">{{ p.label }}</span>
                <span class="block text-xs opacity-70">{{ p.bestFor }}</span>
                <span class="look-specimen" :style="specimen(p.config).card" aria-hidden="true">
                  <span class="look-specimen-aa" :style="specimen(p.config).heading">Aa</span>
                  <span class="min-w-0 truncate text-[11px]" :style="specimen(p.config).body">{{ p.config.fonts.display }} · {{ p.config.shadow.style }} shadow</span>
                  <span class="look-specimen-btn" :style="specimen(p.config).button">Buy</span>
                </span>
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
                :class="{ 'chip-active': draft.style.id === k.id }" :aria-pressed="draft.style.id === k.id" :title="k.bestFor" :data-test="`style-${k.id}`" @click="applyStyle(k.id)" @mouseenter="tryStyle(k.id)" @focus="tryStyle(k.id)" @mouseleave="endTry" @blur="endTry">
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
              <button v-for="p in THEME_PRESETS" :key="p.id" type="button" role="listitem" class="colour-chip" :class="{ 'chip-active': draft.preset === p.id }" :aria-pressed="draft.preset === p.id" :title="p.label" @click="applyColourTheme(p.id)" @mouseenter="tryPalettes(p.label, p.config.light, p.config.dark)" @focus="tryPalettes(p.label, p.config.light, p.config.dark)" @mouseleave="endTry" @blur="endTry">
                <span class="flex h-4 w-12 overflow-hidden rounded" aria-hidden="true">
                  <span class="flex-1" :style="{ background: p.config[mode].background }" />
                  <span class="flex-1" :style="{ background: p.config[mode].primary }" />
                  <span class="flex-1" :style="{ background: p.config[mode].accent }" />
                </span>
                <span class="text-xs">{{ p.label }}</span>
              </button>
            </div>

            <details class="combo-box mb-3" open>
              <summary class="cursor-pointer text-sm font-medium">
                Suggested colour combinations
                <span class="text-xs font-normal opacity-60">· colours only, fonts and shapes stay · {{ COLOUR_COMBOS.length }} pairs, contrast-checked</span>
              </summary>
              <div class="mt-2 flex flex-wrap items-center gap-1" role="group" aria-label="Colour combination category">
                <button type="button" class="cat-chip" :class="{ 'cat-chip-on': comboCategory === 'all' }" :aria-pressed="comboCategory === 'all'" @click="comboCategory = 'all'">All {{ COLOUR_COMBOS.length }}</button>
                <button v-for="cat in COLOUR_CATEGORIES" :key="cat" type="button" class="cat-chip" :class="{ 'cat-chip-on': comboCategory === cat }" :aria-pressed="comboCategory === cat" :data-test="`combo-cat-${cat}`" @click="comboCategory = cat">
                  {{ cat }} <span class="opacity-60">{{ comboCounts.get(cat) }}</span>
                </button>
                <input v-model="comboFilter" type="search" class="input input-bordered input-xs ml-auto w-44" placeholder="Search: coffee, neon…" aria-label="Search colour combinations" data-test="combo-search" />
              </div>
              <p v-if="filteredCombos.length === 0" class="mt-2 text-sm opacity-70">No combination matches.</p>
              <div class="combo-grid combo-scroll mt-2" role="list" aria-label="Colour combinations">
                <button v-for="c in filteredCombos" :key="c.id" type="button" role="listitem" class="combo" :title="`${c.label}: ${c.mood}`" :data-test="`combo-${c.id}`" @click="applyCombo(c.id)" @mouseenter="tryPalettes(c.label, c.light, c.dark)" @focus="tryPalettes(c.label, c.light, c.dark)" @mouseleave="endTry" @blur="endTry">
                  <span class="combo-swatch" aria-hidden="true">
                    <span :style="{ background: c[mode].background }" />
                    <span :style="{ background: c[mode].primary }" />
                    <span :style="{ background: c[mode].accent }" />
                    <span :style="{ background: c[mode].surface }" />
                  </span>
                  <span class="text-xs font-medium leading-tight">{{ c.label }}</span>
                  <span class="text-[10px] leading-tight opacity-60">{{ c.mood }}</span>
                </button>
              </div>
            </details>

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
              <div v-if="harmonySuggestions.length">
                <span class="text-xs opacity-70">Harmonies of this colour (colour-wheel rules), click to apply to both modes:</span>
                <div class="mt-1 flex flex-wrap gap-2" role="list" aria-label="Colour harmonies">
                  <button v-for="h in harmonySuggestions" :key="h.value" type="button" role="listitem" class="colour-chip" :title="h.hint" :data-test="`harmony-${h.value}`" @click="applyHarmony(h.value)" @mouseenter="tryPalettes(h.label, h.light, h.dark)" @focus="tryPalettes(h.label, h.light, h.dark)" @mouseleave="endTry" @blur="endTry">
                    <span class="flex h-4 w-14 overflow-hidden rounded" aria-hidden="true">
                      <span class="flex-1" :style="{ background: h[mode].background }" />
                      <span class="flex-1" :style="{ background: h[mode].primary }" />
                      <span class="flex-1" :style="{ background: h[mode].accent }" />
                    </span>
                    <span class="text-xs">{{ h.label }}</span>
                  </button>
                </div>
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
            <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
              <p class="text-sm opacity-70">Heading and body typefaces for the storefront and this console. {{ FONT_PAIRS.length }} tested pairings first; mix your own below.</p>
              <input v-model="fontFilter" type="search" class="input input-bordered input-sm w-56" placeholder="Filter: serif, luxury, readable…" aria-label="Filter font pairings" />
            </div>
            <p v-if="filteredFontPairs.length === 0" class="mb-4 text-sm opacity-70">No pairing matches "{{ fontFilter }}".</p>
            <div class="mb-4 grid max-h-[28rem] gap-2 overflow-y-auto pr-1 sm:grid-cols-2" role="list">
              <button v-for="fp in filteredFontPairs" :key="fp.id" type="button" role="listitem" class="font-pair"
                :class="{ 'chip-active': draft.fonts.display === fp.display && draft.fonts.body === fp.body }"
                :aria-pressed="draft.fonts.display === fp.display && draft.fonts.body === fp.body" @click="applyFontPair(fp.id)" @mouseenter="tryFontPair(fp.id)" @focus="tryFontPair(fp.id)" @mouseleave="endTry" @blur="endTry">
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
                  <optgroup v-for="g in fontGroups" :key="g.label" :label="g.label">
                    <option v-for="f in g.fonts" :key="f" :value="f">{{ f }}</option>
                  </optgroup>
                </select>
              </label>
              <label class="form-control">
                <span class="label-text mb-1 text-sm">Body font</span>
                <select v-model="draft.fonts.body" class="select select-bordered select-sm" @change="touch">
                  <optgroup v-for="g in fontGroups" :key="g.label" :label="g.label">
                    <option v-for="f in g.fonts" :key="f" :value="f">{{ f }}</option>
                  </optgroup>
                </select>
              </label>
            </div>
            <p class="mt-2 text-xs opacity-60">Fonts other than "system-ui" load from Google Fonts on first visit (one small stylesheet request).</p>

            <h3 class="mb-2 mt-5 text-base font-semibold">Fine-tuning</h3>
            <div class="grid gap-4">
              <ChoiceGroup label="Heading weight" :choices="HEADING_WEIGHTS" :model-value="draft.fonts.headingWeight" test-prefix="hweight" compact
                @update:model-value="(v) => setPart('fonts', { headingWeight: v })" @preview="(v, l) => previewPart('fonts', v && { headingWeight: v }, l)" />
              <ChoiceGroup label="Heading letter spacing" :choices="TRACKINGS" :model-value="draft.fonts.tracking" test-prefix="tracking" compact
                @update:model-value="(v) => setPart('fonts', { tracking: v })" @preview="(v, l) => previewPart('fonts', v && { tracking: v }, l)" />
              <ChoiceGroup label="Body line height" :choices="LINE_HEIGHTS" :model-value="draft.fonts.lineHeight" test-prefix="leading" compact
                @update:model-value="(v) => setPart('fonts', { lineHeight: v })" @preview="(v, l) => previewPart('fonts', v && { lineHeight: v }, l)" />
            </div>
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
            <p class="mb-3 text-sm opacity-70">Corner roundness, outline thickness and outline colour. Pills and circles stay round.</p>
            <div class="mb-5 grid gap-4">
              <ChoiceGroup label="Corners" :choices="RADIUS_KINDS" :model-value="draft.border.radius" test-prefix="radius" :swatch="swatch.radius" @update:model-value="setRadius" @preview="(v, l) => previewPart('border', v && { radius: v }, l)" />
              <ChoiceGroup label="Outline thickness" :choices="BORDER_WIDTHS" :model-value="draft.border.width" test-prefix="width" :swatch="swatch.width" @update:model-value="setBorderWidth" @preview="(v, l) => previewPart('border', v && { width: v }, l)" />
              <ChoiceGroup label="Outline colour" :choices="BORDER_COLORS" :model-value="draft.border.color" test-prefix="bcolor" :swatch="swatch.borderColour" @update:model-value="(v) => setPart('border', { color: v })" @preview="(v, l) => previewPart('border', v && { color: v }, l)" />
              <ChoiceGroup label="Line style" :choices="BORDER_STYLES" :model-value="draft.border.style" test-prefix="bstyle" :swatch="swatch.borderStyle" @update:model-value="(v) => setPart('border', { style: v })" @preview="(v, l) => previewPart('border', v && { style: v }, l)" />
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
              <button v-for="sh in SHADOW_STYLES" :key="sh.value" type="button" class="option-row" :class="{ 'chip-active': draft.shadow.style === sh.value }" :aria-pressed="draft.shadow.style === sh.value" :data-test="`shadow-${sh.value}`" @click="setShadow(sh.value)" @mouseenter="previewPart('shadow', { style: sh.value }, `Shadow: ${sh.label}`)" @focus="previewPart('shadow', { style: sh.value }, `Shadow: ${sh.label}`)" @mouseleave="endTry" @blur="endTry">
                <span class="shadow-swatch" :style="swatch.shadow(sh.value)" aria-hidden="true" />
                <span class="text-sm font-medium">{{ sh.label }}</span>
                <span class="text-xs opacity-60">{{ sh.hint }}</span>
              </button>
            </div>
            <div class="mt-4 grid gap-4" :class="{ 'opacity-50': draft.shadow.style === 'none' }">
              <ChoiceGroup label="Shadow strength" :choices="SHADOW_INTENSITIES" :model-value="draft.shadow.intensity" test-prefix="sintensity" :swatch="swatch.intensity" compact @update:model-value="(v) => setPart('shadow', { intensity: v })" @preview="(v, l) => previewPart('shadow', v && { intensity: v }, l)" />
              <ChoiceGroup label="Shadow colour" :choices="SHADOW_TINTS" :model-value="draft.shadow.tint" test-prefix="stint" :swatch="swatch.tint" compact @update:model-value="(v) => setPart('shadow', { tint: v })" @preview="(v, l) => previewPart('shadow', v && { tint: v }, l)" />
              <p v-if="draft.shadow.style === 'none'" class="text-xs">Pick a shadow style above to use strength and colour.</p>
            </div>
          </template>

          <!-- ===== Cards & buttons ===== -->
          <template v-else-if="activeTab === 'components'">
            <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                Cards
                <span v-if="sectionDirty.cards" class="badge badge-warning badge-sm">Changed</span>
              </h2>
              <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('cards')">Reset cards</button>
            </div>
            <p class="mb-3 text-sm opacity-70">Product tiles, panels and dashboard cards. Hover effects apply to clickable cards only; hover over the preview cards to try them.</p>
            <div class="mb-5 grid gap-4">
              <ChoiceGroup label="Card fill" :choices="CARD_FILLS" :model-value="draft.card.fill" test-prefix="cfill" :swatch="swatch.cardFill" @update:model-value="(v) => setPart('card', { fill: v })" @preview="(v, l) => previewPart('card', v && { fill: v }, l)" />
              <ChoiceGroup label="Hover effect" :choices="CARD_HOVERS" :model-value="draft.card.hover" test-prefix="chover" @update:model-value="(v) => setPart('card', { hover: v })" @preview="(v, l) => previewPart('card', v && { hover: v }, l)" />
              <ChoiceGroup label="Card shape" :choices="CARD_SHAPES" :model-value="draft.card.shape" test-prefix="cshape" :swatch="swatch.cardShape" @update:model-value="(v) => setPart('card', { shape: v })" @preview="(v, l) => previewPart('card', v && { shape: v }, l)" />
              <ChoiceGroup label="Accent stripe" :choices="CARD_ACCENTS" :model-value="draft.card.accent" test-prefix="caccent" :swatch="swatch.cardAccent" @update:model-value="(v) => setPart('card', { accent: v })" @preview="(v, l) => previewPart('card', v && { accent: v }, l)" />
            </div>

            <div class="mb-1 flex flex-wrap items-center justify-between gap-2">
              <h2 class="flex items-center gap-2 text-lg font-semibold">
                Buttons
                <span v-if="sectionDirty.buttons" class="badge badge-warning badge-sm">Changed</span>
              </h2>
              <button class="btn btn-ghost btn-xs" type="button" @click="resetSection('buttons')">Reset buttons</button>
            </div>
            <p class="mb-3 text-sm opacity-70">Main call-to-action buttons (Add to cart, Checkout, primary console actions).</p>
            <div class="grid gap-4">
              <ChoiceGroup label="Shape" :choices="BUTTON_SHAPES" :model-value="draft.button.shape" test-prefix="bshape" :swatch="swatch.buttonShape" @update:model-value="(v) => setPart('button', { shape: v })" @preview="(v, l) => previewPart('button', v && { shape: v }, l)" />
              <ChoiceGroup label="Fill" :choices="BUTTON_FILLS" :model-value="draft.button.fill" test-prefix="bfill" :swatch="swatch.buttonFill" @update:model-value="(v) => setPart('button', { fill: v })" @preview="(v, l) => previewPart('button', v && { fill: v }, l)" />
              <ChoiceGroup label="Label style" :choices="BUTTON_TEXTS" :model-value="draft.button.text" test-prefix="btext" compact @update:model-value="(v) => setPart('button', { text: v })" @preview="(v, l) => previewPart('button', v && { text: v }, l)" />
              <ChoiceGroup label="Hover effect" :choices="BUTTON_HOVERS" :model-value="draft.button.hover" test-prefix="bhover" @update:model-value="(v) => setPart('button', { hover: v })" @preview="(v, l) => previewPart('button', v && { hover: v }, l)" />
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

          <nav class="step-nav" aria-label="Previous and next section">
            <button v-if="prevTab" class="btn btn-ghost btn-sm" type="button" data-test="prev-tab" @click="goToTab(prevTab.id)">← {{ prevTab.label }}</button>
            <span class="flex-1 text-center text-xs opacity-60">{{ tabIndex + 1 }} of {{ TABS.length }}</span>
            <button v-if="nextTab" class="btn btn-outline btn-sm" type="button" data-test="next-tab" @click="goToTab(nextTab.id)">Next: {{ nextTab.label }} →</button>
            <button v-else class="btn btn-primary btn-sm" type="button" :disabled="!canSave" @click="requestPublish">Review &amp; publish</button>
          </nav>
        </section>
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
          <p class="trial-banner" :class="{ 'trial-banner-on': trial }" role="status" aria-live="polite" data-test="trial">
            <template v-if="trial">Trying <b>{{ trial.label }}</b> · click to apply</template>
            <template v-else>Hover or focus any option to try it here first.</template>
          </p>
          <ThemePreview :config="previewConfig" :published="published" :mode="mode" :dirty="dirty || !!trial" />
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
.trial-banner {
  margin: 0 0 0.5rem;
  padding: 0.3rem 0.55rem;
  font-size: 0.75rem;
  border-radius: var(--radius-field);
  background: var(--color-base-200);
  opacity: 0.75;
  transition: background-color 150ms ease, opacity 150ms ease;
}
.trial-banner-on {
  opacity: 1;
  background: color-mix(in srgb, var(--color-primary) 16%, var(--color-base-100));
  box-shadow: inset 0 0 0 1px var(--color-primary);
}
.theme-toast {
  position: fixed;
  right: 1rem;
  bottom: 1rem;
  z-index: 60;
  max-width: min(28rem, calc(100vw - 2rem));
}
.step-nav {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 1.25rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--color-base-300);
}
.look-specimen {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.5rem;
  padding: 0.45rem 0.55rem;
}
.look-specimen-aa {
  font-size: 1.35rem;
  line-height: 1;
}
.look-specimen-btn {
  margin-left: auto;
  padding: 0.15rem 0.6rem;
  font-size: 0.7rem;
  font-weight: 700;
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

/*
 * Section navigation. The editor column is a size container, so the layout follows the room the
 * editor actually has (it changes when the preview is docked or wide), not the window width:
 *  - roomy:  a sticky, grouped side menu with one-line hints;
 *  - narrow: a single sticky line  ← [Section · 4 of 9 ▾] →  whose ▾ opens the same grouped menu.
 * Nothing ever scrolls sideways.
 */
.editor-col {
  container: editor / inline-size;
}
.nav-host {
  display: block;
}
.section-rail {
  display: none;
}
.rail-group {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.rail-group + .rail-group {
  margin-top: 0.6rem;
}
.rail-group-name {
  padding: 0 0.55rem 0.15rem;
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  opacity: 0.55;
}
.rail-item {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  width: 100%;
  text-align: left;
  padding: 0.4rem 0.55rem;
  border-radius: var(--radius-field);
  color: var(--color-base-content);
  transition:
    background-color 150ms ease,
    box-shadow 150ms ease;
}
.rail-item:hover {
  background: color-mix(in srgb, var(--color-base-content) 6%, transparent);
}
.rail-item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.rail-item-active,
.rail-item-active:hover {
  background: color-mix(in srgb, var(--color-primary) 12%, var(--color-base-100));
  box-shadow: inset 3px 0 0 var(--color-primary);
}
.rail-item-active .rail-label {
  color: var(--color-primary);
}
.rail-icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 1.6rem;
  height: 1.6rem;
  font-size: 0.78rem;
  font-weight: 700;
  border-radius: 0.4rem;
  background: var(--color-base-200);
}
.rail-item-active .rail-icon {
  background: var(--color-primary);
  color: var(--color-primary-content);
}
.rail-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}
.rail-label {
  font-size: 0.85rem;
  font-weight: 600;
  line-height: 1.2;
}
.rail-hint {
  overflow: hidden;
  font-size: 0.7rem;
  line-height: 1.25;
  white-space: nowrap;
  text-overflow: ellipsis;
  opacity: 0.6;
}
.tab-dot {
  flex: none;
  width: 0.45rem;
  height: 0.45rem;
  border-radius: 9999px;
  background: var(--color-warning);
}

/* Narrow: one sticky line under the action bar. */
.section-switcher {
  position: sticky;
  top: 3.75rem;
  z-index: 15;
  display: flex;
  align-items: center;
  gap: 0.35rem;
  margin-bottom: 0.75rem;
  padding: 0.3rem;
  background: var(--color-base-100);
  border: 1px solid var(--color-base-300);
  border-radius: var(--radius-box);
  box-shadow: 0 4px 12px -8px rgb(0 0 0 / 0.25);
}
.switcher-menu-root {
  position: relative;
  flex: 1;
  min-width: 0;
}
.switcher-current {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  min-width: 0;
  padding: 0.3rem 0.55rem;
  font-size: 0.875rem;
  border-radius: var(--radius-field);
}
.switcher-current:hover,
.switcher-current[aria-expanded='true'] {
  background: var(--color-base-200);
}
.switcher-current:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.switcher-menu {
  position: absolute;
  top: calc(100% + 0.4rem);
  left: 0;
  right: 0;
  z-index: 30;
  max-height: min(70vh, 32rem);
  overflow-y: auto;
  padding: 0.5rem;
  background: var(--color-base-100);
  border: 1px solid var(--color-base-300);
  border-radius: var(--radius-box);
  box-shadow: 0 16px 40px -12px rgb(0 0 0 / 0.35);
}

/* Roomy: side menu beside the panel; the one-line switcher is not needed. */
@container editor (min-width: 760px) {
  .nav-host {
    display: grid;
    grid-template-columns: 13.5rem minmax(0, 1fr);
    gap: 1rem;
    align-items: start;
  }
  .section-rail {
    position: sticky;
    top: 4.25rem;
    display: block;
    max-height: calc(100vh - 5rem);
    overflow-y: auto;
    padding: 0.5rem;
    background: var(--color-base-100);
    border: 1px solid var(--color-base-300);
    border-radius: var(--radius-box);
  }
  .section-switcher {
    display: none;
  }
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
.sw-layered { box-shadow: 0 1px 2px rgb(0 0 0 / 0.1), 0 2px 4px rgb(0 0 0 / 0.1), 0 4px 8px rgb(0 0 0 / 0.1), 0 8px 16px rgb(0 0 0 / 0.1); }
.sw-glow { box-shadow: 0 0 0 1px color-mix(in srgb, var(--color-primary) 20%, transparent), 0 4px 16px color-mix(in srgb, var(--color-primary) 55%, transparent); }
.sw-long { box-shadow: 7px 7px 0 var(--color-base-content); }
.cat-chip {
  padding: 0.15rem 0.55rem;
  font-size: 0.75rem;
  border: 1px solid var(--color-base-300);
  border-radius: 9999px;
  background: var(--color-base-100);
  white-space: nowrap;
}
.cat-chip:hover,
.cat-chip:focus-visible {
  border-color: var(--color-primary);
}
.cat-chip-on {
  border-color: var(--color-primary);
  background: color-mix(in srgb, var(--color-primary) 14%, var(--color-base-100));
  color: var(--color-primary);
  font-weight: 600;
}
.combo-scroll {
  max-height: 26rem;
  overflow-y: auto;
  padding-right: 0.25rem;
}
.combo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(8.5rem, 1fr));
  gap: 0.45rem;
}
.combo {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  text-align: left;
  padding: 0.4rem;
  border: 2px solid var(--color-base-300);
  border-radius: var(--radius-field);
  background: var(--color-base-100);
  transition: border-color 150ms ease;
}
.combo:hover,
.combo:focus-visible {
  border-color: var(--color-primary);
}
.combo-swatch {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 0.6fr;
  height: 1.5rem;
  overflow: hidden;
  border-radius: 0.35rem;
  border: 1px solid var(--color-base-300);
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
