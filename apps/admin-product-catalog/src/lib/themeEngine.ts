/**
 * Runtime theme engine - turns an admin-authored `ThemeConfig` (6 colours per mode, fonts, radius,
 * brand, optional custom CSS) into CSS variables for either the admin console or the storefront.
 *
 * THIS FILE IS DELIBERATELY IDENTICAL IN BOTH APPS
 *   apps/admin-product-catalog/src/lib/themeEngine.ts
 *   apps/product-catalog/src/lib/themeEngine.ts
 * (the two apps share no package; the repo already mirrors tokens by hand). Change one, change both.
 * Only `target` differs at call sites.
 *
 * "No config" means "inject nothing": the built-in Space-Age Atomic stylesheet stays byte-for-byte
 * what it was, so an unconfigured install is unaffected by this feature.
 */

export type ThemeTarget = 'admin' | 'storefront';
export type RadiusKind = 'sharp' | 'soft' | 'round';
export type BorderWidth = 'thin' | 'standard' | 'bold';
export type ShadowStyle = 'none' | 'soft' | 'crisp' | 'retro' | 'lifted';

/** Design languages. 'default' = the built-in look (nothing injected). Must match ThemeController. */
export const STYLE_IDS = [
  'default', 'minimalism', 'flat-design', 'flat-2', 'material', 'fluent', 'hig', 'dark-mode',
  'skeuomorphism', 'neumorphism', 'glassmorphism', 'claymorphism', 'liquid-glass', 'aurora-ui',
  'bento-grid', 'editorial-swiss', 'brutalism', 'neubrutalism', 'retro-vintage', 'y2k',
  'cyberpunk', 'bauhaus', 'maximalism', 'spatial-ui', 'conversational-ai',
] as const;
export type StyleId = (typeof STYLE_IDS)[number];

/**
 * What a style does at RUNTIME, on top of the colour/font/border/shadow sections:
 *  - surface:  the "material" of cards, panels and dialogs (glass blur, inset neumorphic light...)
 *  - backdrop: decoration behind the page (aurora wash, grid, dots, scanlines...)
 *  - heading:  casing/tracking of headings
 *  - button:   flat or glowing call-to-action buttons
 * Everything else a style implies (palette, fonts, corners) is a starter kit in themeStyles.ts.
 */
export interface StyleFx {
  readonly surface: 'solid' | 'glass' | 'liquid' | 'neu' | 'clay' | 'skeuo';
  readonly backdrop: 'none' | 'aurora' | 'grid' | 'dots' | 'scanlines' | 'spatial' | 'paper';
  readonly heading: 'normal' | 'upper' | 'tight';
  readonly button: 'default' | 'flat' | 'glow';
}

const fx = (
  surface: StyleFx['surface'],
  backdrop: StyleFx['backdrop'],
  heading: StyleFx['heading'],
  button: StyleFx['button'],
): StyleFx => ({ surface, backdrop, heading, button });

export const STYLE_FX: Record<StyleId, StyleFx> = {
  default: fx('solid', 'none', 'normal', 'default'),
  minimalism: fx('solid', 'none', 'normal', 'flat'),
  'flat-design': fx('solid', 'none', 'normal', 'flat'),
  'flat-2': fx('solid', 'none', 'normal', 'default'),
  material: fx('solid', 'none', 'normal', 'default'),
  fluent: fx('glass', 'spatial', 'normal', 'default'),
  hig: fx('glass', 'none', 'normal', 'flat'),
  'dark-mode': fx('solid', 'none', 'normal', 'default'),
  skeuomorphism: fx('skeuo', 'paper', 'normal', 'default'),
  neumorphism: fx('neu', 'none', 'normal', 'flat'),
  glassmorphism: fx('glass', 'aurora', 'normal', 'default'),
  claymorphism: fx('clay', 'none', 'normal', 'default'),
  'liquid-glass': fx('liquid', 'aurora', 'normal', 'default'),
  'aurora-ui': fx('glass', 'aurora', 'normal', 'default'),
  'bento-grid': fx('solid', 'none', 'normal', 'default'),
  'editorial-swiss': fx('solid', 'grid', 'tight', 'flat'),
  brutalism: fx('solid', 'none', 'upper', 'flat'),
  neubrutalism: fx('solid', 'dots', 'tight', 'default'),
  'retro-vintage': fx('solid', 'paper', 'normal', 'default'),
  y2k: fx('liquid', 'grid', 'normal', 'glow'),
  cyberpunk: fx('solid', 'scanlines', 'upper', 'glow'),
  bauhaus: fx('solid', 'grid', 'upper', 'flat'),
  maximalism: fx('solid', 'dots', 'tight', 'default'),
  'spatial-ui': fx('glass', 'spatial', 'normal', 'default'),
  'conversational-ai': fx('solid', 'aurora', 'normal', 'flat'),
};

export type TextScale = 'small' | 'default' | 'large';
export type MotionLevel = 'full' | 'reduced' | 'none';

export const TEXT_SCALES: readonly { value: TextScale; label: string; hint: string }[] = [
  { value: 'small', label: 'Compact', hint: 'About 6% smaller everywhere' },
  { value: 'default', label: 'Default', hint: 'As designed' },
  { value: 'large', label: 'Large', hint: 'About 9% larger, easier reading' },
];
export const MOTION_LEVELS: readonly { value: MotionLevel; label: string; hint: string }[] = [
  { value: 'full', label: 'Full', hint: 'All animations and transitions' },
  { value: 'reduced', label: 'Reduced', hint: 'No looping decoration, short transitions' },
  { value: 'none', label: 'None', hint: 'Everything static' },
];

export const RADIUS_KINDS: readonly { value: RadiusKind; label: string; hint: string }[] = [
  { value: 'sharp', label: 'Sharp', hint: 'Square, 2-4px corners' },
  { value: 'soft', label: 'Soft', hint: 'Built-in rounded cards' },
  { value: 'round', label: 'Round', hint: 'Extra-round, friendly' },
];
export const BORDER_WIDTHS: readonly { value: BorderWidth; label: string; hint: string }[] = [
  { value: 'thin', label: 'Thin', hint: '1px outlines, light and airy' },
  { value: 'standard', label: 'Standard', hint: '2px printed-catalog outlines' },
  { value: 'bold', label: 'Bold', hint: '3px outlines, poster style' },
];
export const SHADOW_STYLES: readonly { value: ShadowStyle; label: string; hint: string }[] = [
  { value: 'none', label: 'Flat', hint: 'No shadows at all' },
  { value: 'soft', label: 'Soft', hint: 'Subtle blurred elevation' },
  { value: 'crisp', label: 'Crisp', hint: 'Small hard offset' },
  { value: 'retro', label: 'Retro', hint: 'Built-in hard offset print shadow' },
  { value: 'lifted', label: 'Lifted', hint: 'Large, diffuse floating cards' },
];

export interface ThemePalette {
  primary: string;
  accent: string;
  background: string;
  surface: string;
  text: string;
  danger: string;
}

export interface ThemeConfig {
  preset: string;
  brand: { name: string; tagline: string; logoUrl: string };
  light: ThemePalette;
  dark: ThemePalette;
  /** Corner radius and outline thickness. */
  border: { radius: RadiusKind; width: BorderWidth };
  shadow: { style: ShadowStyle };
  /** Design language: surface material, backdrop, heading and button treatment. */
  style: { id: StyleId };
  /** Global comfort options. */
  options: { textScale: TextScale; motion: MotionLevel };
  fonts: { display: string; body: string };
  customCss: string;
}

/**
 * Must match the allow-list in ThemeController.java. Every family here ships weights 400-700 on
 * Google Fonts: the stylesheet request asks for 400;500;600;700 and Google rejects the WHOLE request
 * if one weight is missing, so single-weight display faces (Bebas Neue...) are deliberately absent.
 */
export const FONT_OPTIONS = [
  'Quicksand', 'Poppins', 'Inter', 'DM Sans', 'Manrope', 'Space Grotesk', 'Playfair Display', 'Lora',
  'Nunito', 'Montserrat', 'Raleway', 'Work Sans', 'Rubik', 'Outfit', 'Plus Jakarta Sans', 'Sora',
  'Josefin Sans', 'Cormorant Garamond', 'Fraunces', 'Archivo', 'Karla', 'Source Sans 3',
  'IBM Plex Sans', 'Oswald', 'system-ui',
] as const;

const SERIF_FONTS: readonly string[] = ['Playfair Display', 'Lora', 'Cormorant Garamond', 'Fraunces'];

export interface FontPair {
  readonly id: string;
  readonly label: string;
  readonly vibe: string;
  readonly display: string;
  readonly body: string;
}

/** Tested-together heading/body combinations offered as one click in the editor. */
export const FONT_PAIRS: readonly FontPair[] = [
  { id: 'atomic', label: 'Quicksand + Poppins', vibe: 'Friendly, rounded, retro (built-in)', display: 'Quicksand', body: 'Poppins' },
  { id: 'clean-tech', label: 'Inter + Inter', vibe: 'Neutral, dense, product UI', display: 'Inter', body: 'Inter' },
  { id: 'editorial', label: 'Playfair Display + DM Sans', vibe: 'Magazine-style, premium', display: 'Playfair Display', body: 'DM Sans' },
  { id: 'modern-geo', label: 'Space Grotesk + Inter', vibe: 'Design-led, techy', display: 'Space Grotesk', body: 'Inter' },
  { id: 'warm-serif', label: 'Fraunces + Work Sans', vibe: 'Warm, crafted, lifestyle', display: 'Fraunces', body: 'Work Sans' },
  { id: 'luxe', label: 'Cormorant Garamond + Karla', vibe: 'Elegant, boutique, fashion', display: 'Cormorant Garamond', body: 'Karla' },
  { id: 'sporty', label: 'Oswald + Source Sans 3', vibe: 'Bold, condensed, athletic', display: 'Oswald', body: 'Source Sans 3' },
  { id: 'friendly-round', label: 'Nunito + Nunito', vibe: 'Soft, approachable, family', display: 'Nunito', body: 'Nunito' },
  { id: 'startup', label: 'Sora + Plus Jakarta Sans', vibe: 'Fresh, confident, SaaS-like', display: 'Sora', body: 'Plus Jakarta Sans' },
  { id: 'playful', label: 'Outfit + Rubik', vibe: 'Fun, energetic, kids', display: 'Outfit', body: 'Rubik' },
  { id: 'classic-sans', label: 'Montserrat + Source Sans 3', vibe: 'Classic retail, readable', display: 'Montserrat', body: 'Source Sans 3' },
  { id: 'airy', label: 'Raleway + Lora', vibe: 'Light, graceful, beauty', display: 'Raleway', body: 'Lora' },
  { id: 'corporate', label: 'IBM Plex Sans + IBM Plex Sans', vibe: 'Precise, technical, B2B', display: 'IBM Plex Sans', body: 'IBM Plex Sans' },
  { id: 'geometric-soft', label: 'Josefin Sans + Manrope', vibe: 'Vintage-geometric, stylish', display: 'Josefin Sans', body: 'Manrope' },
  { id: 'bold-grotesk', label: 'Archivo + Archivo', vibe: 'Strong, newsy, no-nonsense', display: 'Archivo', body: 'Archivo' },
  { id: 'system', label: 'System UI', vibe: 'Fastest; no download', display: 'system-ui', body: 'system-ui' },
];

export const PALETTE_KEYS = ['primary', 'accent', 'background', 'surface', 'text', 'danger'] as const;
export type PaletteKey = (typeof PALETTE_KEYS)[number];

export const DEFAULT_THEME: ThemeConfig = {
  preset: 'atomic',
  brand: { name: 'Cartwise', tagline: 'Shop smarter', logoUrl: '' },
  light: {
    primary: '#176d65',
    accent: '#f4a662',
    background: '#f6efda',
    surface: '#fffbf0',
    text: '#1d2b3a',
    danger: '#c81930',
  },
  dark: {
    primary: '#4dcbbb',
    accent: '#f6a85a',
    background: '#0d1521',
    surface: '#162131',
    text: '#f7f1de',
    danger: '#f25f72',
  },
  border: { radius: 'soft', width: 'standard' },
  shadow: { style: 'retro' },
  style: { id: 'default' },
  options: { textScale: 'default', motion: 'full' },
  fonts: { display: 'Quicksand', body: 'Poppins' },
  customCss: '',
};

// ---- colour maths ---------------------------------------------------------------------------

type Rgb = readonly [number, number, number];
const HEX_RE = /^#[0-9a-f]{6}$/i;

export function isHex(v: unknown): v is string {
  return typeof v === 'string' && HEX_RE.test(v);
}

function hexToRgb(hex: string): Rgb {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function rgbToHex([r, g, b]: Rgb): string {
  const c = (x: number) => Math.max(0, Math.min(255, Math.round(x))).toString(16).padStart(2, '0');
  return `#${c(r)}${c(g)}${c(b)}`;
}

function rgbToHsl([r, g, b]: Rgb): [number, number, number] {
  const R = r / 255;
  const G = g / 255;
  const B = b / 255;
  const max = Math.max(R, G, B);
  const min = Math.min(R, G, B);
  const l = (max + min) / 2;
  const d = max - min;
  if (d === 0) return [0, 0, l * 100];
  const s = d / (1 - Math.abs(2 * l - 1));
  let h: number;
  if (max === R) h = ((G - B) / d) % 6;
  else if (max === G) h = (B - R) / d + 2;
  else h = (R - G) / d + 4;
  return [(h * 60 + 360) % 360, s * 100, l * 100];
}

function hslToRgb(h: number, s: number, l: number): Rgb {
  const S = s / 100;
  const L = l / 100;
  const a = S * Math.min(L, 1 - L);
  const f = (n: number) => {
    const k = (n + h / 30) % 12;
    return (L - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1)))) * 255;
  };
  return [f(0), f(8), f(4)];
}

/** Mix `a` toward `b` by `t` (0..1) in RGB. */
export function mix(a: string, b: string, t: number): string {
  const x = hexToRgb(a);
  const y = hexToRgb(b);
  return rgbToHex([x[0] + (y[0] - x[0]) * t, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t]);
}

function shiftLightness(hex: string, delta: number): string {
  const [h, s, l] = rgbToHsl(hexToRgb(hex));
  return rgbToHex(hslToRgb(h, s, Math.max(0, Math.min(100, l + delta))));
}

function shiftHue(hex: string, delta: number): string {
  const [h, s, l] = rgbToHsl(hexToRgb(hex));
  return rgbToHex(hslToRgb((h + delta + 360) % 360, s, l));
}

/** "H S% L%" triple, the storefront's token format (consumed as `hsl(var(--x) / a)`). */
export function toTriple(hex: string): string {
  const [h, s, l] = rgbToHsl(hexToRgb(hex));
  return `${Math.round(h)} ${Math.round(s)}% ${Math.round(l)}%`;
}

function luminance(hex: string): number {
  const [r, g, b] = hexToRgb(hex).map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2.x contrast ratio, 1..21. */
export function contrast(a: string, b: string): number {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** Text colour for use ON `bg`: prefer the palette's own tints, fall back to black/white. */
function readableOn(bg: string, p: ThemePalette): string {
  const candidates = [p.surface, p.text, '#ffffff', '#111111'];
  let best = candidates[0]!;
  for (const c of candidates) {
    if (contrast(bg, c) >= 4.5) return c;
    if (contrast(bg, c) > contrast(bg, best)) best = c;
  }
  return best;
}

// ---- token derivation -----------------------------------------------------------------------

export interface DerivedTokens {
  readonly background: string;
  readonly surface: string;
  readonly surfaceVariant: string;
  readonly outline: string;
  readonly onSurface: string;
  readonly muted: string;
  readonly ink: string;
  readonly brand: string;
  readonly brand2: string;
  readonly onBrand: string;
  readonly primary: string;
  readonly onPrimary: string;
  readonly primaryContainer: string;
  readonly onPrimaryContainer: string;
  readonly accent: string;
  readonly accent2: string;
  readonly accentStrong: string;
  readonly onAccent: string;
  readonly deal: string;
  readonly deal2: string;
  readonly error: string;
  readonly onError: string;
}

export function deriveTokens(p: ThemePalette, mode: 'light' | 'dark'): DerivedTokens {
  const dark = mode === 'dark';
  const primaryContainer = mix(p.surface, p.primary, dark ? 0.28 : 0.18);
  return {
    background: p.background,
    surface: p.surface,
    surfaceVariant: mix(p.surface, p.text, dark ? 0.14 : 0.1),
    outline: dark ? mix(p.surface, p.text, 0.42) : mix(p.text, p.background, 0.1),
    onSurface: p.text,
    muted: mix(p.text, p.background, 0.38),
    ink: dark ? mix(p.background, '#000000', 0.6) : p.text,
    brand: p.primary,
    brand2: shiftLightness(p.primary, dark ? -6 : 5),
    onBrand: readableOn(p.primary, p),
    primary: p.primary,
    onPrimary: readableOn(p.primary, p),
    primaryContainer,
    onPrimaryContainer: readableOn(primaryContainer, p),
    accent: p.accent,
    accent2: shiftHue(p.accent, -14),
    accentStrong: shiftLightness(p.accent, -8),
    onAccent: readableOn(p.accent, p),
    deal: p.danger,
    deal2: shiftLightness(p.danger, dark ? -10 : -6),
    error: p.danger,
    onError: readableOn(p.danger, p),
  };
}

// ---- palette tools (editor conveniences) ----------------------------------------------------

const clampL = (l: number) => Math.max(0, Math.min(100, l));

/** A complete, accessible palette built from one brand colour. */
export function generatePalette(base: string, mode: 'light' | 'dark'): ThemePalette {
  const [h, s0] = rgbToHsl(hexToRgb(base));
  const s = Math.max(35, Math.min(80, s0));
  const hex = (hue: number, sat: number, l: number) => rgbToHex(hslToRgb((hue + 360) % 360, sat, clampL(l)));
  const dark = mode === 'dark';
  return autoFixPalette(
    {
      primary: hex(h, s, dark ? 68 : 34),
      accent: hex(h + 150, Math.min(85, s + 10), dark ? 66 : 56),
      background: hex(h, dark ? 38 : 32, dark ? 8 : 96),
      surface: hex(h, dark ? 32 : 40, dark ? 12 : 99),
      text: hex(h, dark ? 25 : 35, dark ? 94 : 12),
      danger: dark ? '#f25f72' : '#c81930',
    },
    mode,
  );
}

/** Dark counterpart of a light palette: same hues, inverted lightness. */
export function deriveDarkFromLight(light: ThemePalette): ThemePalette {
  const flip = (hex: string, target: number, satScale = 1) => {
    const [h, s] = rgbToHsl(hexToRgb(hex));
    return rgbToHex(hslToRgb(h, Math.min(100, s * satScale), target));
  };
  return autoFixPalette(
    {
      primary: flip(light.primary, 68),
      accent: flip(light.accent, 66),
      background: flip(light.background, 8, 0.9),
      surface: flip(light.surface, 12, 0.8),
      text: flip(light.text, 94, 0.7),
      danger: '#f25f72',
    },
    'dark',
  );
}

/** Nudge lightness until every checked pairing passes (text 4.5:1, primary/danger 3:1 on surface). */
export function autoFixPalette(p: ThemePalette, mode: 'light' | 'dark'): ThemePalette {
  const towards = mode === 'dark' ? 1 : -1; // text/primary move toward the contrasting end
  const shift = (hex: string, step: number) => shiftLightness(hex, step);
  const fixAgainst = (fg: string, bgs: string[], min: number, dir: number): string => {
    let out = fg;
    for (let i = 0; i < 40 && bgs.some((b) => contrast(out, b) < min); i++) out = shift(out, dir * 2.5);
    return out;
  };
  const text = fixAgainst(p.text, [p.background, p.surface], 4.5, towards);
  const primary = fixAgainst(p.primary, [p.surface], 3, towards);
  const danger = fixAgainst(p.danger, [p.surface], 3, towards);
  return { ...p, text, primary, danger };
}

/** A pleasant random palette (for exploring). */
export function randomPalette(mode: 'light' | 'dark', rand: () => number = Math.random): ThemePalette {
  const h = Math.floor(rand() * 360);
  const s = 45 + Math.floor(rand() * 35);
  return generatePalette(rgbToHex(hslToRgb(h, s, 45)), mode);
}

// ---- validation helpers (editor) ------------------------------------------------------------

export interface ContrastCheck {
  readonly label: string;
  readonly ratio: number;
  readonly min: number;
  readonly ok: boolean;
}

export function contrastChecks(p: ThemePalette): ContrastCheck[] {
  const t = deriveTokens(p, 'light');
  const rows: [string, number, number][] = [
    ['Text on background', contrast(p.text, p.background), 4.5],
    ['Text on surface', contrast(p.text, p.surface), 4.5],
    ['Button text on primary', contrast(t.onPrimary, p.primary), 4.5],
    ['Button text on accent', contrast(t.onAccent, p.accent), 4.5],
    ['Primary links on surface', contrast(p.primary, p.surface), 3],
    ['Danger on surface', contrast(p.danger, p.surface), 3],
  ];
  return rows.map(([label, ratio, min]) => ({ label, ratio, min, ok: ratio >= min }));
}

const FORBIDDEN_CSS = ['@import', 'url(', 'expression', 'javascript:', 'behavior', 'binding', '<', '>', '\\'];

/** Returns the reason the CSS is rejected, or null when it is acceptable. Mirrors ThemeController. */
export function customCssProblem(css: string): string | null {
  if (css.length > 4000) return 'Custom CSS is limited to 4000 characters.';
  const lower = css.toLowerCase();
  const hit = FORBIDDEN_CSS.find((bad) => lower.includes(bad));
  return hit ? `Custom CSS must not contain "${hit}".` : null;
}

// ---- normalising untrusted input ------------------------------------------------------------

function pickPalette(raw: unknown, fallback: ThemePalette): ThemePalette {
  const r = (raw ?? {}) as Partial<Record<PaletteKey, unknown>>;
  const out = { ...fallback } as Record<PaletteKey, string>;
  for (const k of PALETTE_KEYS) {
    const v = r[k];
    if (isHex(v)) out[k] = v.toLowerCase();
  }
  return out;
}

function oneOf<T extends string>(v: unknown, allowed: readonly T[], fallback: T): T {
  return typeof v === 'string' && (allowed as readonly string[]).includes(v) ? (v as T) : fallback;
}

interface RawBrand {
  name?: unknown;
  tagline?: unknown;
  logoUrl?: unknown;
}
interface RawFonts {
  display?: unknown;
  body?: unknown;
}
interface RawTheme {
  preset?: unknown;
  brand?: unknown;
  light?: unknown;
  dark?: unknown;
  radius?: unknown; // legacy (pre-border) configs
  border?: unknown;
  shadow?: unknown;
  style?: unknown;
  options?: unknown;
  fonts?: unknown;
  customCss?: unknown;
}

/** Defensive merge of whatever the API / localStorage / a pasted JSON gave us. */
export function normalizeTheme(raw: unknown): ThemeConfig {
  const r = (raw ?? {}) as RawTheme;
  const brand = (r.brand ?? {}) as RawBrand;
  const fonts = (r.fonts ?? {}) as RawFonts;
  const border = (r.border ?? {}) as { radius?: unknown; width?: unknown };
  const shadow = (r.shadow ?? {}) as { style?: unknown };
  const designStyle = (r.style ?? {}) as { id?: unknown };
  const options = (r.options ?? {}) as { textScale?: unknown; motion?: unknown };
  const font = (v: unknown, fb: string) =>
    typeof v === 'string' && (FONT_OPTIONS as readonly string[]).includes(v) ? v : fb;
  const str = (v: unknown, fb: string, max: number) => (typeof v === 'string' ? v.slice(0, max) : fb);
  const css = str(r.customCss, '', 4000);
  return {
    preset: str(r.preset, 'custom', 40),
    brand: {
      name: str(brand.name, DEFAULT_THEME.brand.name, 40).trim() || DEFAULT_THEME.brand.name,
      tagline: str(brand.tagline, '', 120),
      logoUrl: str(brand.logoUrl, '', 500),
    },
    light: pickPalette(r.light, DEFAULT_THEME.light),
    dark: pickPalette(r.dark, DEFAULT_THEME.dark),
    border: {
      radius: oneOf(border.radius ?? r.radius, ['sharp', 'soft', 'round'], 'soft'),
      width: oneOf(border.width, ['thin', 'standard', 'bold'], 'standard'),
    },
    style: { id: oneOf(designStyle.id, STYLE_IDS, 'default') },
    options: {
      textScale: oneOf(options.textScale, ['small', 'default', 'large'], 'default'),
      motion: oneOf(options.motion, ['full', 'reduced', 'none'], 'full'),
    },
    shadow: { style: oneOf(shadow.style, ['none', 'soft', 'crisp', 'retro', 'lifted'], DEFAULT_THEME.shadow.style) },
    fonts: {
      display: font(fonts.display, DEFAULT_THEME.fonts.display),
      body: font(fonts.body, DEFAULT_THEME.fonts.body),
    },
    customCss: customCssProblem(css) ? '' : css,
  };
}

// ---- CSS generation -------------------------------------------------------------------------

const hsl = (hex: string) => `hsl(${toTriple(hex)})`;

function fontStack(name: string): string {
  if (name === 'system-ui') return "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";
  const serif = SERIF_FONTS.includes(name);
  const tail = serif ? "Georgia, 'Times New Roman', serif" : "system-ui, -apple-system, 'Segoe UI', sans-serif";
  return `'${name}', ${tail}`;
}

/** Starburst wallpaper tinted with the theme's own two colours (replaces the hard-coded teal/orange). */
function patternUri(c1: string, c2: string): string {
  const e = (c: string) => c.replace('#', '%23');
  return (
    `url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' width='180' height='180' viewBox='0 0 180 180'>` +
    `<polygon points='44.0,36.0 44.6,50.5 51.0,45.0 45.5,51.4 60.0,52.0 45.5,52.6 51.0,59.0 44.6,53.5 44.0,68.0 43.4,53.5 37.0,59.0 42.5,52.6 28.0,52.0 42.5,51.4 37.0,45.0 43.4,50.5' fill='${e(c1)}'/>` +
    `<polygon points='132.0,115.0 132.8,123.2 137.6,124.0 132.8,124.8 132.0,133.0 131.2,124.8 126.4,124.0 131.2,123.2' fill='${e(c2)}'/>` +
    `<circle cx='150' cy='34' r='3' fill='${e(c2)}'/><circle cx='86' cy='152' r='2.4' fill='${e(c1)}'/>` +
    `<circle cx='22' cy='138' r='3.2' fill='${e(c2)}'/><circle cx='110' cy='74' r='1.8' fill='${e(c1)}'/></svg>")`
  );
}

const RADIUS_REM = { xs: 0.125, sm: 0.25, md: 0.375, lg: 0.5, xl: 0.75, '2xl': 1, '3xl': 1.5, '4xl': 2 } as const;

/** The five tile hues, keeping their identity but borrowing the palette's saturation/lightness feel. */
function vividTokens(primary: string, mode: 'light' | 'dark'): string[] {
  const [, s] = rgbToHsl(hexToRgb(primary));
  const sat = Math.max(40, Math.min(72, s));
  const light = mode === 'dark' ? 62 : 46;
  const hues: [string, number][] = [['sky', 196], ['violet', 268], ['pink', 345], ['coral', 12], ['lime', 78]];
  return hues.map(([name, h]) => `--color-vivid-${name}: ${Math.round(h)} ${Math.round(sat)}% ${light}%`);
}

function storefrontBlock(t: DerivedTokens, mode: 'light' | 'dark', harmonise: boolean): string {
  const tr = toTriple;
  const lines = [
    `--color-background: ${tr(t.background)}`,
    `--color-surface: ${tr(t.surface)}`,
    `--color-surface-variant: ${tr(t.surfaceVariant)}`,
    `--color-outline: ${tr(t.outline)}`,
    `--color-on-surface: ${tr(t.onSurface)}`,
    `--color-on-surface-muted: ${tr(t.muted)}`,
    `--color-ink: ${tr(t.ink)}`,
    `--color-brand: ${tr(t.brand)}`,
    `--color-brand-2: ${tr(t.brand2)}`,
    `--color-on-brand: ${tr(t.onBrand)}`,
    `--color-primary: ${tr(t.primary)}`,
    `--color-on-primary: ${tr(t.onPrimary)}`,
    `--color-primary-container: ${tr(t.primaryContainer)}`,
    `--color-on-primary-container: ${tr(t.onPrimaryContainer)}`,
    `--color-accent: ${tr(t.accent)}`,
    `--color-accent-2: ${tr(t.accent2)}`,
    `--color-accent-strong: ${tr(t.accentStrong)}`,
    `--color-on-accent: ${tr(t.onAccent)}`,
    `--color-deal: ${tr(t.deal)}`,
    `--color-deal-2: ${tr(t.deal2)}`,
    `--color-error: ${tr(t.error)}`,
    `--color-on-error: ${tr(t.onError)}`,
    `--color-vivid-amber: ${tr(t.accent)}`,
    `--color-vivid-teal: ${tr(t.primary)}`,
    ...(harmonise ? vividTokens(t.primary, mode) : []),
    `--pattern-image: ${patternUri(mode === 'dark' ? t.onSurface : t.primary, mode === 'dark' ? t.primary : t.accent)}`,
  ];
  return lines.join(';\n  ');
}

function adminBlock(t: DerivedTokens, mode: 'light' | 'dark'): string {
  const h = hsl;
  const lines = [
    `--color-base-100: ${h(t.surface)}`,
    `--color-base-200: ${h(t.background)}`,
    `--color-base-300: ${h(t.surfaceVariant)}`,
    `--color-base-content: ${h(t.onSurface)}`,
    `--color-primary: ${h(t.primary)}`,
    `--color-primary-content: ${h(t.onPrimary)}`,
    `--color-secondary: ${h(t.accent)}`,
    `--color-secondary-content: ${h(t.onAccent)}`,
    `--color-accent: ${h(t.accent2)}`,
    `--color-accent-content: ${h(t.onAccent)}`,
    `--color-neutral: ${h(mode === 'dark' ? t.onSurface : t.ink)}`,
    `--color-neutral-content: ${h(mode === 'dark' ? t.background : t.surface)}`,
    `--color-error: ${h(t.error)}`,
    `--color-error-content: ${h(t.onError)}`,
    `--cw-ink: ${h(t.ink)}`,
    `--cw-outline: ${h(t.outline)}`,
    `--cw-brand: ${h(t.brand)}`,
    `--cw-brand-2: ${h(t.brand2)}`,
    `--cw-on-brand: ${h(t.onBrand)}`,
    `--cw-accent: ${h(t.accent)}`,
    `--cw-accent-2: ${h(t.accent2)}`,
    `--cw-on-accent: ${h(t.onAccent)}`,
    `--cw-amber: ${h(t.accent)}`,
    `--cw-deal: ${h(t.deal)}`,
    `--cw-pattern: ${patternUri(mode === 'dark' ? t.onSurface : t.primary, mode === 'dark' ? t.primary : t.accent)}`,
  ];
  return lines.join(';\n  ');
}

function radiusCss(kind: RadiusKind, target: ThemeTarget): string {
  if (target === 'admin') {
    const v =
      kind === 'sharp'
        ? ['0.25rem', '0.125rem', '0.25rem']
        : kind === 'round'
          ? ['1.75rem', '1.25rem', '9999px']
          : ['1.25rem', '0.75rem', '9999px'];
    return `:root:root{--radius-box:${v[0]};--radius-field:${v[1]};--radius-selector:${v[2]}}`;
  }
  if (kind === 'soft') return '';
  const f = kind === 'sharp' ? 0.2 : 1.5;
  const vars = Object.entries(RADIUS_REM)
    .map(([k, rem]) => `--radius-${k}:${(rem * f).toFixed(3)}rem`)
    .join(';');
  // Fixed-size utilities Tailwind does not route through the --radius-* scale. `rounded-full`
  // (pills, avatars, circles) is intentionally left alone so round things stay round.
  const scaled = (rem: number) => `calc(${rem}rem * ${f})`;
  // CSS class selectors need `[`, `]` and `.` escaped with a backslash.
  const bs = String.fromCharCode(92);
  const cls = (rem: number) => `${bs}[${String(rem).replace('.', bs + '.')}rem${bs}]`;
  const fixed = [1.5, 1.75, 2, 2.5]
    .map((rem) => `.rounded-${cls(rem)}{border-radius:${scaled(rem)}}`)
    .join('');
  const sides =
    `.rounded-r-${cls(2)}{border-radius:0 ${scaled(2)} ${scaled(2)} 0}` +
    `.rounded-l-${cls(2)}{border-radius:${scaled(2)} 0 0 ${scaled(2)}}`;
  return `:root:root{${vars}}.rounded{border-radius:${scaled(0.25)}}${fixed}${sides}`;
}

interface ShadowTokens {
  readonly card: string;
  readonly hover: string;
  readonly cta: string;
}

/** `ink(a)` is the theme's ink colour at alpha `a` (1 = solid). */
function shadowTokens(style: ShadowStyle, ink: (alpha: number) => string): ShadowTokens | null {
  switch (style) {
    case 'none':
      return { card: 'none', hover: 'none', cta: 'none' };
    case 'soft':
      return {
        card: `0 2px 8px ${ink(0.12)}`,
        hover: `0 8px 22px ${ink(0.18)}`,
        cta: `0 2px 6px ${ink(0.2)}`,
      };
    case 'crisp':
      return { card: `2px 2px 0 ${ink(1)}`, hover: `4px 4px 0 ${ink(1)}`, cta: `1px 1px 0 ${ink(1)}` };
    case 'lifted':
      return {
        card: `0 10px 30px -8px ${ink(0.28)}`,
        hover: `0 18px 40px -10px ${ink(0.35)}`,
        cta: `0 6px 16px -4px ${ink(0.3)}`,
      };
    default:
      return null; // 'retro' is the built-in stylesheet's own look: emit nothing
  }
}

function shadowCss(style: ShadowStyle, target: ThemeTarget): string {
  if (target === 'admin') {
    const t = shadowTokens(style, (a) => (a >= 1 ? 'var(--cw-ink)' : `color-mix(in srgb, var(--cw-ink) ${Math.round(a * 100)}%, transparent)`));
    return t ? `:root:root{--cw-shadow-card:${t.card};--cw-shadow-card-hover:${t.hover};--cw-shadow-cta:${t.cta}}` : '';
  }
  const t = shadowTokens(style, (a) => (a >= 1 ? 'hsl(var(--color-ink))' : `hsl(var(--color-ink) / ${a})`));
  return t ? `:root:root{--shadow-card:${t.card};--shadow-card-hover:${t.hover};--shadow-cta:${t.cta}}` : '';
}

/**
 * Outline thickness. Only the 2px utilities (`border-2`, `border-t-2`...) are rescaled: the 1px
 * hairlines stay hairlines, and unlayered overrides of plain `.border` would beat combos such as
 * `border border-b-0`.
 */
function borderWidthCss(width: BorderWidth, target: ThemeTarget): string {
  if (width === 'standard') return '';
  if (target === 'admin') {
    const px = width === 'thin' ? '1px' : '3px';
    return `:root:root{--cw-bw:${px};--border:${px}}`;
  }
  const px = width === 'thin' ? '1px' : '3px';
  const sides: [string, string][] = [
    ['', 'border-width'],
    ['-t', 'border-top-width'],
    ['-b', 'border-bottom-width'],
    ['-l', 'border-left-width'],
    ['-r', 'border-right-width'],
    ['-x', 'border-inline-width'],
    ['-y', 'border-block-width'],
  ];
  return sides.map(([suffix, prop]) => `.border${suffix}-2{${prop}:${px}}`).join('');
}

// ---- design style (surface material, backdrop, headings, buttons) ---------------------------

const FX_SELECTORS = {
  storefront: {
    surface: '.retro-card,.shadow-card,.bg-surface',
    page: 'body,.bg-background',
    heading: 'h1,h2,h3,.font-display',
    ctaShadow: '.shadow-cta',
    ctaFill: '.bg-cta-gradient',
  },
  admin: {
    surface: '.data-card,.data-card-elevated,.stat-card,.modal-box',
    page: 'body,.bg-base-200',
    heading: 'h1,h2,h3,.font-display',
    ctaShadow: '.btn:not(.btn-ghost):not(.btn-link):not(.btn-square):not(.btn-circle)',
    ctaFill: '.btn.btn-primary',
  },
} as const;

/** Mode-dependent colours the style rules read, defined next to the main tokens. */
function fxVars(t: DerivedTokens, mode: 'light' | 'dark'): string {
  const dark = mode === 'dark';
  return [
    `--fx-surface:${t.surface}`,
    `--fx-bg:${t.background}`,
    `--fx-text:${t.onSurface}`,
    `--fx-primary:${t.primary}`,
    `--fx-accent:${t.accent}`,
    `--fx-ink:${t.ink}`,
    `--fx-hi:${dark ? 'rgba(255,255,255,.07)' : 'rgba(255,255,255,.85)'}`,
    `--fx-lo:${dark ? 'rgba(0,0,0,.6)' : 'rgba(30,40,70,.18)'}`,
  ].join(';');
}

const mixVar = (v: string, pct: number) => `color-mix(in srgb,var(${v}) ${pct}%,transparent)`;

function surfaceRule(kind: StyleFx['surface']): string {
  switch (kind) {
    case 'glass':
      return `background:${mixVar('--fx-surface', 58)};backdrop-filter:blur(16px) saturate(150%);-webkit-backdrop-filter:blur(16px) saturate(150%);border-color:${mixVar('--fx-text', 16)};box-shadow:0 8px 32px -8px var(--fx-lo)`;
    case 'liquid':
      return `background:linear-gradient(135deg,${mixVar('--fx-surface', 62)},${mixVar('--fx-surface', 38)});backdrop-filter:blur(24px) saturate(190%) brightness(1.04);-webkit-backdrop-filter:blur(24px) saturate(190%) brightness(1.04);border-color:color-mix(in srgb,white 45%,transparent);box-shadow:inset 0 1px 0 color-mix(in srgb,white 70%,transparent),inset 0 -1px 0 color-mix(in srgb,white 15%,transparent),0 14px 36px -12px var(--fx-lo)`;
    case 'neu':
      return 'background:var(--fx-bg);border-color:transparent;box-shadow:8px 8px 18px var(--fx-lo),-8px -8px 18px var(--fx-hi)';
    case 'clay':
      return `border-color:transparent;box-shadow:inset -6px -6px 12px ${mixVar('--fx-text', 10)},inset 6px 6px 12px var(--fx-hi),10px 14px 26px -6px var(--fx-lo)`;
    case 'skeuo':
      return 'background:linear-gradient(180deg,color-mix(in srgb,var(--fx-surface) 86%,white),var(--fx-surface) 55%,color-mix(in srgb,var(--fx-surface) 90%,black));box-shadow:inset 0 1px 0 var(--fx-hi),inset 0 -2px 4px var(--fx-lo),0 2px 4px var(--fx-lo),0 12px 20px -10px var(--fx-lo)';
    default:
      return '';
  }
}

function backdropRule(kind: StyleFx['backdrop']): string {
  const line = mixVar('--fx-text', 7);
  switch (kind) {
    case 'aurora':
      return `background-image:radial-gradient(60rem 38rem at 8% -8%,${mixVar('--fx-primary', 34)},transparent 62%),radial-gradient(48rem 34rem at 96% 2%,${mixVar('--fx-accent', 30)},transparent 62%),radial-gradient(52rem 40rem at 50% 112%,color-mix(in srgb,var(--fx-primary) 22%,var(--fx-accent) 22%),transparent 62%);background-attachment:fixed`;
    case 'grid':
      return `background-image:linear-gradient(${line} 1px,transparent 1px),linear-gradient(90deg,${line} 1px,transparent 1px);background-size:32px 32px`;
    case 'dots':
      return `background-image:radial-gradient(${mixVar('--fx-text', 15)} 1.3px,transparent 1.4px);background-size:20px 20px`;
    case 'scanlines':
      return `background-image:repeating-linear-gradient(0deg,${mixVar('--fx-text', 6)} 0 1px,transparent 1px 3px)`;
    case 'spatial':
      return `background-image:radial-gradient(80rem 46rem at 50% -12%,${mixVar('--fx-primary', 24)},transparent 70%);background-attachment:fixed`;
    case 'paper':
      return `background-image:repeating-linear-gradient(45deg,${mixVar('--fx-text', 3)} 0 1px,transparent 1px 5px)`;
    default:
      return '';
  }
}

function buttonRules(kind: StyleFx['button'], sel: (typeof FX_SELECTORS)[ThemeTarget]): string {
  if (kind === 'flat') {
    return `${sel.ctaShadow}{box-shadow:none}${sel.ctaFill}{background-image:none;background-color:var(--fx-accent)}`;
  }
  if (kind === 'glow') {
    return `${sel.ctaShadow}{box-shadow:0 0 18px ${mixVar('--fx-accent', 65)},0 0 3px var(--fx-accent)}`;
  }
  return '';
}

function styleCss(id: StyleId, cfg: ThemeConfig, target: ThemeTarget): string {
  if (id === 'default') return '';
  const f = STYLE_FX[id];
  const sel = FX_SELECTORS[target];
  const light = deriveTokens(cfg.light, 'light');
  const dark = deriveTokens(cfg.dark, 'dark');
  const modeSel =
    target === 'storefront'
      ? [':root:root', ':root:root[data-theme="dark"]']
      : [":root:root[data-theme='catalogLight']", ":root:root[data-theme='catalogDark']"];
  const parts = [`${modeSel[0]}{${fxVars(light, 'light')}}`, `${modeSel[1]}{${fxVars(dark, 'dark')}}`];
  const surface = surfaceRule(f.surface);
  if (surface) parts.push(`${sel.surface}{${surface}}`);
  const backdrop = backdropRule(f.backdrop);
  if (backdrop) parts.push(`${sel.page}{${backdrop}}`);
  if (f.heading === 'upper') parts.push(`${sel.heading}{text-transform:uppercase;letter-spacing:.04em}`);
  if (f.heading === 'tight') parts.push(`${sel.heading}{letter-spacing:-.02em;font-weight:800}`);
  const buttons = buttonRules(f.button, sel);
  if (buttons) parts.push(buttons);
  return parts.join('');
}

function optionsCss(o: ThemeConfig['options'], target: ThemeTarget): string {
  const parts: string[] = [];
  if (o.textScale !== 'default') {
    parts.push(`html{font-size:${o.textScale === 'small' ? 94 : 109}%}`);
    // The storefront pins body to 16px; let it follow the root size instead.
    if (target === 'storefront') parts.push('body{font-size:1rem}');
  }
  if (o.motion === 'reduced') {
    parts.push('*,*::before,*::after{animation-iteration-count:1!important;transition-duration:150ms!important;scroll-behavior:auto!important}');
  } else if (o.motion === 'none') {
    parts.push('*,*::before,*::after{animation:none!important;transition:none!important;scroll-behavior:auto!important}');
  }
  return parts.join('');
}

/** Full stylesheet for a config. Pure, so it is testable and the editor can preview with it. */
export function buildThemeCss(cfg: ThemeConfig, target: ThemeTarget): string {
  const light = deriveTokens(cfg.light, 'light');
  const dark = deriveTokens(cfg.dark, 'dark');
  const display = fontStack(cfg.fonts.display);
  const body = fontStack(cfg.fonts.body);
  const parts: string[] = [];

  if (target === 'storefront') {
    parts.push(`:root:root{\n  ${storefrontBlock(light, 'light', cfg.preset !== 'atomic')}\n}`);
    parts.push(`:root:root[data-theme="dark"]{\n  ${storefrontBlock(dark, 'dark', cfg.preset !== 'atomic')}\n}`);
    parts.push(
      `body,.font-body{font-family:${body}}` +
        `h1,h2,h3,.font-display{font-family:${display}}` +
        `h3.font-body{font-family:${body}}`,
    );
  } else {
    parts.push(`:root:root[data-theme='catalogLight']{\n  ${adminBlock(light, 'light')}\n}`);
    parts.push(`:root:root[data-theme='catalogDark']{\n  ${adminBlock(dark, 'dark')}\n}`);
    parts.push(`:root:root{--font-sans:${body};--font-display:${display}}`);
  }
  for (const extra of [
    radiusCss(cfg.border.radius, target),
    borderWidthCss(cfg.border.width, target),
    shadowCss(cfg.shadow.style, target),
    styleCss(cfg.style.id, cfg, target),
    optionsCss(cfg.options, target),
  ]) {
    if (extra) parts.push(extra);
  }
  if (cfg.customCss && !customCssProblem(cfg.customCss)) parts.push(cfg.customCss);
  return parts.join('\n');
}

// ---- DOM application ------------------------------------------------------------------------

const STYLE_ID = 'cw-theme';
const FONT_LINK_ID = 'cw-theme-fonts';
export const THEME_CACHE_KEY = 'cw_theme_v1';

function fontsHref(cfg: ThemeConfig): string | null {
  const names = [...new Set([cfg.fonts.display, cfg.fonts.body])].filter((n) => n !== 'system-ui');
  if (names.length === 0) return null;
  const fam = names.map((n) => `family=${encodeURIComponent(n).replace(/%20/g, '+')}:wght@400;500;600;700`);
  return `https://fonts.googleapis.com/css2?${fam.join('&')}&display=swap`;
}

/** Injects (or replaces) the theme stylesheet + font link. */
export function applyTheme(cfg: ThemeConfig, target: ThemeTarget): void {
  if (typeof document === 'undefined') return;
  let style = document.getElementById(STYLE_ID) as HTMLStyleElement | null;
  if (!style) {
    style = document.createElement('style');
    style.id = STYLE_ID;
    document.head.appendChild(style);
  }
  style.textContent = buildThemeCss(cfg, target);

  const href = fontsHref(cfg);
  let link = document.getElementById(FONT_LINK_ID) as HTMLLinkElement | null;
  if (href) {
    if (!link) {
      link = document.createElement('link');
      link.id = FONT_LINK_ID;
      link.rel = 'stylesheet';
      document.head.appendChild(link);
    }
    if (link.href !== href) link.href = href;
  } else {
    link?.remove();
  }

  const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (icon) {
    if (!icon.hasAttribute('data-cw-original')) icon.setAttribute('data-cw-original', icon.getAttribute('href') ?? '');
    icon.removeAttribute('type');
    icon.setAttribute('href', cfg.brand.logoUrl || (icon.getAttribute('data-cw-original') ?? ''));
  }

  const meta = document.querySelector('meta[name="theme-color"]');
  meta?.setAttribute('content', cfg.light.primary);
}

/** Back to the built-in stylesheet. */
export function clearTheme(): void {
  if (typeof document === 'undefined') return;
  document.getElementById(STYLE_ID)?.remove();
  document.getElementById(FONT_LINK_ID)?.remove();
  const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  const original = icon?.getAttribute('data-cw-original');
  if (icon && original) icon.setAttribute('href', original);
}

export function readCachedTheme(): ThemeConfig | null {
  try {
    const raw = localStorage.getItem(THEME_CACHE_KEY);
    return raw ? normalizeTheme(JSON.parse(raw)) : null;
  } catch {
    return null;
  }
}

export function writeCachedTheme(cfg: ThemeConfig | null): void {
  try {
    if (cfg) localStorage.setItem(THEME_CACHE_KEY, JSON.stringify(cfg));
    else localStorage.removeItem(THEME_CACHE_KEY);
  } catch {
    // Private mode / quota: the theme just won't survive a reload before the network answers.
  }
}

// ---- shareable preview links ----------------------------------------------------------------

/**
 * URL-hash key the storefront watches for a draft to render WITHOUT publishing it:
 * `https://shop.example/#cwpreview=<base64url JSON>`. The storefront paints it for that tab only,
 * never caches it, and shows a "preview" banner. Admins use it to check a draft on the real site.
 */
export const PREVIEW_HASH_KEY = 'cwpreview';

function toBase64Url(s: string): string {
  const bytes = new TextEncoder().encode(s);
  let bin = '';
  bytes.forEach((b) => {
    bin += String.fromCharCode(b);
  });
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(s: string): string {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
  const bin = atob(b64);
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
}

export function encodeThemeForUrl(cfg: ThemeConfig): string {
  return toBase64Url(JSON.stringify(cfg));
}

/** Null when the payload is not a decodable theme; whatever decodes is still run through normalizeTheme. */
export function decodeThemeFromUrl(encoded: string): ThemeConfig | null {
  try {
    return normalizeTheme(JSON.parse(fromBase64Url(encoded)));
  } catch {
    return null;
  }
}

/** Reads `#cwpreview=...` from a location hash (tolerates other `&`-separated hash params). */
export function readPreviewFromHash(hash: string): ThemeConfig | null {
  const part = hash
    .replace(/^#/, '')
    .split('&')
    .find((p) => p.startsWith(PREVIEW_HASH_KEY + '='));
  return part ? decodeThemeFromUrl(part.slice(PREVIEW_HASH_KEY.length + 1)) : null;
}
