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

import { FONT_NAMES, fontCategory } from './themeFonts';

export { FONT_CATALOG, FONT_CATEGORY_LABELS, FONT_PAIRS, fontCategory, type FontCategory, type FontPair } from './themeFonts';

export type ThemeTarget = 'admin' | 'storefront';
export type RadiusKind = 'square' | 'micro' | 'sharp' | 'soft' | 'round' | 'pill' | 'mega';
export type BorderWidth = 'none' | 'thin' | 'standard' | 'bold' | 'heavy';
export type BorderColor = 'subtle' | 'default' | 'strong' | 'brand' | 'accent' | 'muted';
export type BorderStyle = 'solid' | 'dashed' | 'dotted' | 'double';
export type ShadowStyle =
  | 'none'
  | 'soft'
  | 'layered'
  | 'lifted'
  | 'dreamy'
  | 'floating'
  | 'elevated'
  | 'paper'
  | 'glow'
  | 'neon'
  | 'ring'
  | 'inner'
  | 'crisp'
  | 'retro'
  | 'long'
  | 'stacked'
  | 'drop'
  | 'left';
export type ShadowIntensity = 'whisper' | 'light' | 'medium' | 'strong' | 'dramatic';
export type ShadowTint = 'ink' | 'brand' | 'accent' | 'danger' | 'duo';
export type CardHover = 'default' | 'none' | 'lift' | 'float' | 'glow' | 'scale' | 'pop' | 'tilt' | 'press' | 'border' | 'dim';
export type CardFill = 'default' | 'tinted' | 'accent' | 'muted' | 'gradient' | 'glass' | 'outline' | 'dots' | 'stripes' | 'grid';
export type CardShape = 'default' | 'leaf' | 'notch' | 'top';
export type CardAccent = 'none' | 'top' | 'left' | 'bottom';
export type ButtonShape = 'default' | 'square' | 'rounded' | 'squircle' | 'pill' | 'leaf' | 'tab';
export type ButtonFill = 'default' | 'solid' | 'brand' | 'soft' | 'outline' | 'ghost' | 'glass' | 'inverse';
export type ButtonText = 'default' | 'upper' | 'bold' | 'wide' | 'smallcaps';
export type ButtonHover = 'default' | 'none' | 'lift' | 'grow' | 'glow' | 'darken';
export type HeadingWeight = 'default' | 'medium' | 'semibold' | 'bold';
export type Tracking = 'tight' | 'default' | 'wide';
export type LineHeight = 'compact' | 'default' | 'relaxed';

/** One selectable value in the editor; the value lists below are also the normalise allow-lists. */
export interface Choice<T extends string> {
  readonly value: T;
  readonly label: string;
  readonly hint: string;
}

const values = <T extends string>(list: readonly Choice<T>[]): T[] => list.map((c) => c.value);

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

export const RADIUS_KINDS: readonly Choice<RadiusKind>[] = [
  { value: 'square', label: 'Square', hint: 'No rounding at all, architectural' },
  { value: 'micro', label: 'Micro', hint: 'A hint of rounding, 1-2px' },
  { value: 'sharp', label: 'Sharp', hint: '2-4px corners, precise' },
  { value: 'soft', label: 'Soft', hint: 'Built-in rounded cards' },
  { value: 'round', label: 'Round', hint: 'Extra-round, friendly' },
  { value: 'pill', label: 'Pillowy', hint: 'Very round, bubbly and playful' },
  { value: 'mega', label: 'Mega', hint: 'Huge soft curves, toy-like' },
];
export const BORDER_WIDTHS: readonly Choice<BorderWidth>[] = [
  { value: 'none', label: 'Borderless', hint: 'No card outlines; depth comes from shadows' },
  { value: 'thin', label: 'Thin', hint: '1px outlines, light and airy' },
  { value: 'standard', label: 'Standard', hint: '2px printed-catalog outlines' },
  { value: 'bold', label: 'Bold', hint: '3px outlines, poster style' },
  { value: 'heavy', label: 'Heavy', hint: '4px outlines, neubrutalist' },
];
export const BORDER_COLORS: readonly Choice<BorderColor>[] = [
  { value: 'subtle', label: 'Subtle', hint: 'Soft, low-contrast lines' },
  { value: 'muted', label: 'Muted', hint: 'Mid-grey lines, calm' },
  { value: 'default', label: 'Default', hint: 'As the palette derives it' },
  { value: 'strong', label: 'Strong', hint: 'Full text-colour lines' },
  { value: 'brand', label: 'Brand', hint: 'Lines in your primary colour' },
  { value: 'accent', label: 'Accent', hint: 'Lines in your accent colour' },
];
export const BORDER_STYLES: readonly Choice<BorderStyle>[] = [
  { value: 'solid', label: 'Solid', hint: 'Continuous line' },
  { value: 'dashed', label: 'Dashed', hint: 'Coupon / cut-out feel' },
  { value: 'dotted', label: 'Dotted', hint: 'Playful, stitched' },
  { value: 'double', label: 'Double', hint: 'Framed, vintage (best with Bold or Heavy)' },
];
export const SHADOW_STYLES: readonly Choice<ShadowStyle>[] = [
  { value: 'none', label: 'Flat', hint: 'No shadows at all' },
  { value: 'soft', label: 'Soft', hint: 'Subtle blurred elevation' },
  { value: 'layered', label: 'Layered', hint: 'Realistic stacked depth, very smooth' },
  { value: 'lifted', label: 'Lifted', hint: 'Large, diffuse floating cards' },
  { value: 'dreamy', label: 'Dreamy', hint: 'Huge, feather-soft haze' },
  { value: 'floating', label: 'Floating', hint: 'Shadow pooled underneath, hovering' },
  { value: 'elevated', label: 'Elevated', hint: 'Material-style two-layer elevation' },
  { value: 'paper', label: 'Paper', hint: 'Hairline edge plus a tiny drop' },
  { value: 'glow', label: 'Glow', hint: 'Coloured halo in your brand colour' },
  { value: 'neon', label: 'Neon', hint: 'Bright edge with an electric glow' },
  { value: 'ring', label: 'Ring', hint: 'Soft coloured outline ring, no drop' },
  { value: 'inner', label: 'Inset', hint: 'Pressed-in, recessed surfaces' },
  { value: 'crisp', label: 'Crisp', hint: 'Small hard offset' },
  { value: 'retro', label: 'Retro', hint: 'Built-in hard offset print shadow' },
  { value: 'long', label: 'Long', hint: 'Deep hard offset, comic-book' },
  { value: 'stacked', label: 'Stacked', hint: 'Two offset layers, paper stack' },
  { value: 'drop', label: 'Drop', hint: 'Hard shadow straight down, keycap' },
  { value: 'left', label: 'Left', hint: 'Hard offset toward the left' },
];
export const SHADOW_INTENSITIES: readonly Choice<ShadowIntensity>[] = [
  { value: 'whisper', label: 'Whisper', hint: 'Almost invisible' },
  { value: 'light', label: 'Light', hint: 'Barely-there' },
  { value: 'medium', label: 'Medium', hint: 'As designed' },
  { value: 'strong', label: 'Strong', hint: 'Deeper, more dramatic' },
  { value: 'dramatic', label: 'Dramatic', hint: 'Maximum depth' },
];
export const SHADOW_TINTS: readonly Choice<ShadowTint>[] = [
  { value: 'ink', label: 'Ink', hint: 'Neutral dark shadow' },
  { value: 'brand', label: 'Brand', hint: 'Tinted with your primary colour' },
  { value: 'accent', label: 'Accent', hint: 'Tinted with your accent colour' },
  { value: 'danger', label: 'Deal', hint: 'Tinted with your sale colour' },
  { value: 'duo', label: 'Duotone', hint: 'Brand at rest, accent on hover' },
];
export const CARD_HOVERS: readonly Choice<CardHover>[] = [
  { value: 'default', label: 'Built-in', hint: 'Small nudge with a deeper shadow' },
  { value: 'none', label: 'Still', hint: 'Cards do not move on hover' },
  { value: 'lift', label: 'Lift', hint: 'Rises smoothly toward you' },
  { value: 'float', label: 'Float', hint: 'Rises high with a big shadow' },
  { value: 'glow', label: 'Glow', hint: 'Brand-coloured ring and halo' },
  { value: 'scale', label: 'Zoom', hint: 'Grows slightly' },
  { value: 'pop', label: 'Pop', hint: 'Lifts and grows, energetic' },
  { value: 'tilt', label: 'Tilt', hint: 'Playful little twist' },
  { value: 'press', label: 'Press', hint: 'Sinks in, shadow disappears' },
  { value: 'border', label: 'Outline', hint: 'Brand outline appears' },
  { value: 'dim', label: 'Dim', hint: 'Subtle darkening, calm' },
];
export const CARD_FILLS: readonly Choice<CardFill>[] = [
  { value: 'default', label: 'Solid', hint: 'Plain surface colour' },
  { value: 'tinted', label: 'Tinted', hint: 'A whisper of your brand colour' },
  { value: 'accent', label: 'Warm tint', hint: 'A whisper of your accent colour' },
  { value: 'muted', label: 'Muted', hint: 'Slightly grey, recessed' },
  { value: 'gradient', label: 'Gradient', hint: 'Soft surface-to-brand sheen' },
  { value: 'glass', label: 'Frosted', hint: 'See-through, blurred glass' },
  { value: 'outline', label: 'Outline', hint: 'See-through, just a frame' },
  { value: 'dots', label: 'Dotted', hint: 'Fine dot pattern' },
  { value: 'stripes', label: 'Striped', hint: 'Faint diagonal stripes' },
  { value: 'grid', label: 'Grid', hint: 'Blueprint graph paper' },
];
export const CARD_SHAPES: readonly Choice<CardShape>[] = [
  { value: 'default', label: 'Even', hint: 'All corners follow the Corners setting' },
  { value: 'leaf', label: 'Leaf', hint: 'Two round, two tight corners' },
  { value: 'notch', label: 'Notch', hint: 'One square corner, rest round' },
  { value: 'top', label: 'Tab', hint: 'Round top, tight bottom' },
];
export const CARD_ACCENTS: readonly Choice<CardAccent>[] = [
  { value: 'none', label: 'None', hint: 'No accent stripe' },
  { value: 'top', label: 'Top bar', hint: 'Brand stripe along the top' },
  { value: 'left', label: 'Side bar', hint: 'Brand stripe down the left' },
  { value: 'bottom', label: 'Bottom bar', hint: 'Brand stripe along the bottom' },
];
export const BUTTON_SHAPES: readonly Choice<ButtonShape>[] = [
  { value: 'default', label: 'Built-in', hint: 'As each button is designed' },
  { value: 'square', label: 'Square', hint: 'Crisp 4px corners' },
  { value: 'rounded', label: 'Rounded', hint: 'Comfortable 12px corners' },
  { value: 'squircle', label: 'Squircle', hint: 'Soft, app-icon corners' },
  { value: 'pill', label: 'Pill', hint: 'Fully rounded ends' },
  { value: 'leaf', label: 'Leaf', hint: 'Two round, two tight corners' },
  { value: 'tab', label: 'Tab', hint: 'Round top, flat bottom' },
];
export const BUTTON_FILLS: readonly Choice<ButtonFill>[] = [
  { value: 'default', label: 'Gradient', hint: 'Built-in accent gradient' },
  { value: 'solid', label: 'Solid', hint: 'One flat accent colour' },
  { value: 'brand', label: 'Brand', hint: 'Solid primary colour' },
  { value: 'soft', label: 'Soft', hint: 'Pale accent tint, dark text' },
  { value: 'outline', label: 'Outline', hint: 'Transparent with a brand frame' },
  { value: 'ghost', label: 'Ghost', hint: 'Text only, brand colour' },
  { value: 'glass', label: 'Glass', hint: 'Frosted, see-through' },
  { value: 'inverse', label: 'Inverse', hint: 'Text-colour fill, high contrast' },
];
export const BUTTON_TEXTS: readonly Choice<ButtonText>[] = [
  { value: 'default', label: 'Normal', hint: 'Sentence case' },
  { value: 'upper', label: 'Uppercase', hint: 'Spaced capitals' },
  { value: 'bold', label: 'Heavy', hint: 'Extra-bold labels' },
  { value: 'wide', label: 'Wide', hint: 'Generous letter spacing' },
  { value: 'smallcaps', label: 'Small caps', hint: 'Refined, editorial' },
];
export const BUTTON_HOVERS: readonly Choice<ButtonHover>[] = [
  { value: 'default', label: 'Built-in', hint: 'As each button is designed' },
  { value: 'none', label: 'Still', hint: 'No movement' },
  { value: 'lift', label: 'Lift', hint: 'Rises a little' },
  { value: 'grow', label: 'Grow', hint: 'Scales up slightly' },
  { value: 'glow', label: 'Glow', hint: 'Accent halo appears' },
  { value: 'darken', label: 'Darken', hint: 'Fill deepens' },
];export const HEADING_WEIGHTS: readonly Choice<HeadingWeight>[] = [
  { value: 'default', label: 'Built-in', hint: 'Each heading as designed' },
  { value: 'medium', label: 'Medium', hint: '500, light and refined' },
  { value: 'semibold', label: 'Semibold', hint: '600, balanced' },
  { value: 'bold', label: 'Bold', hint: '700, punchy' },
];
export const TRACKINGS: readonly Choice<Tracking>[] = [
  { value: 'tight', label: 'Tight', hint: 'Compact, modern headlines' },
  { value: 'default', label: 'Normal', hint: 'As the font is designed' },
  { value: 'wide', label: 'Wide', hint: 'Airy, luxurious headlines' },
];
export const LINE_HEIGHTS: readonly Choice<LineHeight>[] = [
  { value: 'compact', label: 'Compact', hint: 'Denser paragraphs' },
  { value: 'default', label: 'Normal', hint: 'As designed' },
  { value: 'relaxed', label: 'Relaxed', hint: 'Extra breathing room' },
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
  /** Corner radius, outline thickness and outline colour. */
  border: { radius: RadiusKind; width: BorderWidth; color: BorderColor; style: BorderStyle };
  shadow: { style: ShadowStyle; intensity: ShadowIntensity; tint: ShadowTint };
  /** Product/stat cards: hover behaviour and fill. */
  card: { hover: CardHover; fill: CardFill; shape: CardShape; accent: CardAccent };
  /** Call-to-action buttons. */
  button: { shape: ButtonShape; fill: ButtonFill; text: ButtonText; hover: ButtonHover };
  /** Design language: surface material, backdrop, heading and button treatment. */
  style: { id: StyleId };
  /** Global comfort options. */
  options: { textScale: TextScale; motion: MotionLevel };
  fonts: { display: string; body: string; headingWeight: HeadingWeight; tracking: Tracking; lineHeight: LineHeight };
  customCss: string;
}

/** Every allowed family (see themeFonts.ts). Must match the allow-list in ThemeController.java. */
export const FONT_OPTIONS: readonly string[] = FONT_NAMES;

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
  border: { radius: 'soft', width: 'standard', color: 'default', style: 'solid' },
  shadow: { style: 'retro', intensity: 'medium', tint: 'ink' },
  card: { hover: 'default', fill: 'default', shape: 'default', accent: 'none' },
  button: { shape: 'default', fill: 'default', text: 'default', hover: 'default' },
  style: { id: 'default' },
  options: { textScale: 'default', motion: 'full' },
  fonts: { display: 'Quicksand', body: 'Poppins', headingWeight: 'default', tracking: 'default', lineHeight: 'default' },
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

export type Harmony = 'split' | 'complementary' | 'analogous' | 'triadic' | 'tetradic' | 'monochrome';

/** Colour-wheel relationships between the brand (primary) and accent hues. */
export const HARMONIES: readonly (Choice<Harmony> & { readonly shift: number })[] = [
  { value: 'split', label: 'Split complementary', hint: 'Lively but balanced (default)', shift: 150 },
  { value: 'complementary', label: 'Complementary', hint: 'Opposite hues, maximum pop', shift: 180 },
  { value: 'analogous', label: 'Analogous', hint: 'Neighbouring hues, calm and cohesive', shift: 32 },
  { value: 'triadic', label: 'Triadic', hint: 'Three-way balance, playful', shift: 120 },
  { value: 'tetradic', label: 'Square', hint: 'Bold, rich, energetic', shift: 90 },
  { value: 'monochrome', label: 'Monochrome', hint: 'One hue in many shades, refined', shift: 0 },
];

/** Accent tuned to read well as a button colour in the given mode. */
function tuneAccent(accent: string, mode: 'light' | 'dark'): string {
  const [h, s, l] = rgbToHsl(hexToRgb(accent));
  const L = mode === 'dark' ? Math.max(58, Math.min(72, l)) : Math.max(38, Math.min(58, l));
  return rgbToHex(hslToRgb(h, Math.max(45, s), L));
}

/**
 * A complete, accessible palette built from one brand colour. `opts.harmony` picks the accent hue;
 * `opts.accent` uses a specific accent colour instead (tuned for the mode).
 */
export function generatePalette(
  base: string,
  mode: 'light' | 'dark',
  opts: { harmony?: Harmony; accent?: string } = {},
): ThemePalette {
  const [h, s0] = rgbToHsl(hexToRgb(base));
  const s = Math.max(35, Math.min(80, s0));
  const hex = (hue: number, sat: number, l: number) => rgbToHex(hslToRgb((hue + 360) % 360, sat, clampL(l)));
  const dark = mode === 'dark';
  const shift = HARMONIES.find((x) => x.value === (opts.harmony ?? 'split'))?.shift ?? 150;
  const accent =
    opts.accent && isHex(opts.accent)
      ? tuneAccent(opts.accent, mode)
      : shift === 0
        ? hex(h, Math.min(90, s + 15), dark ? 78 : 50)
        : hex(h + shift, Math.min(85, s + 10), dark ? 66 : 56);
  return autoFixPalette(
    {
      primary: hex(h, s, dark ? 68 : 34),
      accent,
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
  const danger = fixAgainst(p.danger, [p.surface], 3, towards);
  // Button fills: the label colour readableOn() picks must reach 4.5:1.
  const fixFill = (fill: string, pal: ThemePalette): string => {
    let out = fill;
    for (let i = 0; i < 40 && contrast(readableOn(out, pal), out) < 4.5; i++) out = shift(out, towards * 2.5);
    return out;
  };
  const withText = { ...p, text };
  const primary = fixFill(fixAgainst(p.primary, [p.surface], 3, towards), withText);
  const accent = fixFill(p.accent, withText);
  return { ...p, text, primary, accent, danger };
}

/** Light + dark palettes from a primary/accent pair (curated combinations, harmony picks). */
export function paletteFromPair(primary: string, accent: string): { light: ThemePalette; dark: ThemePalette } {
  return {
    light: generatePalette(primary, 'light', { accent }),
    dark: generatePalette(primary, 'dark', { accent }),
  };
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
  card?: unknown;
  button?: unknown;
  style?: unknown;
  options?: unknown;
  fonts?: unknown;
  customCss?: unknown;
}

/** Untrusted object with known keys (explicit keys keep strict index-signature checks happy). */
type Raw<K extends string> = Partial<Record<K, unknown>>;

/** Defensive merge of whatever the API / localStorage / a pasted JSON gave us. */
export function normalizeTheme(raw: unknown): ThemeConfig {
  const r = (raw ?? {}) as RawTheme;
  const brand = (r.brand ?? {}) as RawBrand;
  const fonts = (r.fonts ?? {}) as RawFonts & Raw<'headingWeight' | 'tracking' | 'lineHeight'>;
  const border = (r.border ?? {}) as Raw<'radius' | 'width' | 'color' | 'style'>;
  const shadow = (r.shadow ?? {}) as Raw<'style' | 'intensity' | 'tint'>;
  const card = (r.card ?? {}) as Raw<'hover' | 'fill' | 'shape' | 'accent'>;
  const button = (r.button ?? {}) as Raw<'shape' | 'fill' | 'text' | 'hover'>;
  const D = DEFAULT_THEME;
  const designStyle = (r.style ?? {}) as { id?: unknown };
  const options = (r.options ?? {}) as { textScale?: unknown; motion?: unknown };
  const font = (v: unknown, fb: string) =>
    typeof v === 'string' && FONT_OPTIONS.includes(v) ? v : fb;
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
      radius: oneOf(border.radius ?? r.radius, values(RADIUS_KINDS), D.border.radius),
      width: oneOf(border.width, values(BORDER_WIDTHS), D.border.width),
      color: oneOf(border.color, values(BORDER_COLORS), D.border.color),
      style: oneOf(border.style, values(BORDER_STYLES), D.border.style),
    },
    shadow: {
      style: oneOf(shadow.style, values(SHADOW_STYLES), D.shadow.style),
      intensity: oneOf(shadow.intensity, values(SHADOW_INTENSITIES), D.shadow.intensity),
      tint: oneOf(shadow.tint, values(SHADOW_TINTS), D.shadow.tint),
    },
    card: {
      hover: oneOf(card.hover, values(CARD_HOVERS), D.card.hover),
      fill: oneOf(card.fill, values(CARD_FILLS), D.card.fill),
      shape: oneOf(card.shape, values(CARD_SHAPES), D.card.shape),
      accent: oneOf(card.accent, values(CARD_ACCENTS), D.card.accent),
    },
    button: {
      shape: oneOf(button.shape, values(BUTTON_SHAPES), D.button.shape),
      fill: oneOf(button.fill, values(BUTTON_FILLS), D.button.fill),
      text: oneOf(button.text, values(BUTTON_TEXTS), D.button.text),
      hover: oneOf(button.hover, values(BUTTON_HOVERS), D.button.hover),
    },
    style: { id: oneOf(designStyle.id, STYLE_IDS, 'default') },
    options: {
      textScale: oneOf(options.textScale, values(TEXT_SCALES), 'default'),
      motion: oneOf(options.motion, values(MOTION_LEVELS), 'full'),
    },
    fonts: {
      display: font(fonts.display, D.fonts.display),
      body: font(fonts.body, D.fonts.body),
      headingWeight: oneOf(fonts.headingWeight, values(HEADING_WEIGHTS), D.fonts.headingWeight),
      tracking: oneOf(fonts.tracking, values(TRACKINGS), D.fonts.tracking),
      lineHeight: oneOf(fonts.lineHeight, values(LINE_HEIGHTS), D.fonts.lineHeight),
    },
    customCss: customCssProblem(css) ? '' : css,
  };
}

// ---- CSS generation -------------------------------------------------------------------------

const hsl = (hex: string) => `hsl(${toTriple(hex)})`;

function fontStack(name: string): string {
  if (name === 'system-ui') return "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";
  const tails: Record<string, string> = {
    serif: "Georgia, 'Times New Roman', serif",
    slab: "Rockwell, 'Roboto Slab', Georgia, serif",
    mono: "ui-monospace, 'SFMono-Regular', Menlo, Consolas, monospace",
    script: "'Segoe Print', 'Comic Sans MS', cursive",
  };
  const tail = tails[fontCategory(name)] ?? "system-ui, -apple-system, 'Segoe UI', sans-serif";
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
    const v: Record<RadiusKind, [string, string, string]> = {
      square: ['0', '0', '0'],
      micro: ['0.125rem', '0.0625rem', '0.125rem'],
      sharp: ['0.25rem', '0.125rem', '0.25rem'],
      soft: ['1.25rem', '0.75rem', '9999px'],
      round: ['1.75rem', '1.25rem', '9999px'],
      pill: ['2.25rem', '1.5rem', '9999px'],
      mega: ['3rem', '2rem', '9999px'],
    };
    const [box, field, selector] = v[kind];
    return `:root:root{--radius-box:${box};--radius-field:${field};--radius-selector:${selector}}`;
  }
  if (kind === 'soft') return '';
  const f = { square: 0, micro: 0.1, sharp: 0.2, soft: 1, round: 1.5, pill: 2.1, mega: 3 }[kind];
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

const INTENSITY: Record<ShadowIntensity, number> = { whisper: 0.35, light: 0.55, medium: 1, strong: 1.6, dramatic: 2.3 };

/**
 * `c(a)` is the resting shadow colour at alpha `a` (1 = solid) and `h(a)` the hover colour (they
 * differ only for the duotone tint); `k` scales offsets, blur and alpha. Returns null for the
 * built-in retro shadow at default settings, so nothing is emitted.
 */
function shadowTokens(s: ThemeConfig['shadow'], c: (alpha: number) => string, h: (alpha: number) => string = c): ShadowTokens | null {
  const k = INTENSITY[s.intensity];
  const px = (n: number) => (n === 0 ? '0' : `${Math.max(1, Math.round(n * k))}px`);
  const neg = (n: number) => `-${px(n)}`;
  const al = (col: (a: number) => string, n: number) => col(Math.min(0.9, Math.round(n * k * 100) / 100));
  const a = (n: number) => al(c, n);
  const ha = (n: number) => al(h, n);
  const hard = (x: number, col = c) => `${px(x)} ${px(x)} 0 ${col(1)}`;
  switch (s.style) {
    case 'none':
      return { card: 'none', hover: 'none', cta: 'none' };
    case 'soft':
      return { card: `0 ${px(2)} ${px(8)} ${a(0.12)}`, hover: `0 ${px(8)} ${px(22)} ${ha(0.18)}`, cta: `0 ${px(2)} ${px(6)} ${a(0.2)}` };
    case 'layered':
      return {
        card: [1, 2, 4, 8].map((n) => `0 ${px(n)} ${px(n * 2)} ${a(0.06)}`).join(','),
        hover: [2, 4, 8, 16, 32].map((n) => `0 ${px(n)} ${px(n * 2)} ${ha(0.07)}`).join(','),
        cta: [1, 2, 4].map((n) => `0 ${px(n)} ${px(n * 2)} ${a(0.1)}`).join(','),
      };
    case 'lifted':
      return {
        card: `0 ${px(10)} ${px(30)} ${neg(8)} ${a(0.28)}`,
        hover: `0 ${px(18)} ${px(40)} ${neg(10)} ${ha(0.35)}`,
        cta: `0 ${px(6)} ${px(16)} ${neg(4)} ${a(0.3)}`,
      };
    case 'dreamy':
      return {
        card: `0 ${px(20)} ${px(60)} ${neg(12)} ${a(0.22)}`,
        hover: `0 ${px(30)} ${px(80)} ${neg(14)} ${ha(0.3)}`,
        cta: `0 ${px(10)} ${px(28)} ${neg(8)} ${a(0.3)}`,
      };
    case 'floating':
      return {
        card: `0 ${px(24)} ${px(24)} ${neg(18)} ${a(0.35)}`,
        hover: `0 ${px(34)} ${px(30)} ${neg(20)} ${ha(0.4)}`,
        cta: `0 ${px(12)} ${px(14)} ${neg(8)} ${a(0.35)}`,
      };
    case 'elevated':
      return {
        card: `0 ${px(1)} ${px(3)} ${a(0.12)},0 ${px(1)} ${px(2)} ${a(0.24)}`,
        hover: `0 ${px(10)} ${px(20)} ${ha(0.19)},0 ${px(6)} ${px(6)} ${ha(0.23)}`,
        cta: `0 ${px(3)} ${px(6)} ${a(0.16)},0 ${px(3)} ${px(6)} ${a(0.23)}`,
      };
    case 'paper':
      return {
        card: `0 0 0 1px ${a(0.07)},0 ${px(1)} ${px(2)} ${a(0.09)}`,
        hover: `0 0 0 1px ${ha(0.09)},0 ${px(4)} ${px(10)} ${ha(0.13)}`,
        cta: `0 ${px(1)} ${px(2)} ${a(0.14)}`,
      };
    case 'glow':
      return {
        card: `0 0 0 1px ${a(0.1)},0 ${px(6)} ${px(24)} ${a(0.22)}`,
        hover: `0 0 0 1px ${ha(0.2)},0 ${px(10)} ${px(36)} ${ha(0.34)}`,
        cta: `0 ${px(4)} ${px(18)} ${a(0.4)}`,
      };
    case 'neon':
      return {
        card: `0 0 0 1px ${c(1)},0 0 ${px(12)} ${a(0.5)},0 0 ${px(28)} ${a(0.25)}`,
        hover: `0 0 0 1px ${h(1)},0 0 ${px(16)} ${ha(0.65)},0 0 ${px(40)} ${ha(0.35)}`,
        cta: `0 0 ${px(10)} ${a(0.6)},0 0 ${px(2)} ${c(1)}`,
      };
    case 'ring':
      return { card: `0 0 0 ${px(2)} ${a(0.3)}`, hover: `0 0 0 ${px(3)} ${ha(0.55)}`, cta: `0 0 0 ${px(2)} ${a(0.4)}` };
    case 'inner':
      return {
        card: `inset 0 ${px(2)} ${px(6)} ${a(0.16)}`,
        hover: `inset 0 ${px(3)} ${px(10)} ${ha(0.24)}`,
        cta: `inset 0 ${neg(2)} 0 ${a(0.25)}`,
      };
    case 'crisp':
      return { card: hard(2), hover: hard(4, h), cta: hard(1) };
    case 'long':
      return { card: hard(7), hover: hard(11, h), cta: hard(4) };
    case 'stacked':
      return {
        card: `${hard(3)},${px(6)} ${px(6)} 0 ${a(0.45)}`,
        hover: `${hard(5, h)},${px(10)} ${px(10)} 0 ${ha(0.45)}`,
        cta: `${hard(2)},${px(4)} ${px(4)} 0 ${a(0.45)}`,
      };
    case 'drop':
      return { card: `0 ${px(3)} 0 ${c(1)}`, hover: `0 ${px(6)} 0 ${h(1)}`, cta: `0 ${px(3)} 0 ${c(1)}` };
    case 'left':
      return { card: `${neg(4)} ${px(4)} 0 ${c(1)}`, hover: `${neg(8)} ${px(8)} 0 ${h(1)}`, cta: `${neg(3)} ${px(3)} 0 ${c(1)}` };
    default: // retro: the built-in stylesheet's own look
      return s.intensity === 'medium' && s.tint === 'ink' ? null : { card: hard(4), hover: hard(8, h), cta: hard(3) };
  }
}

type TintKey = 'ink' | 'brand' | 'accent' | 'danger';

/** Resting and hover colours. Glow/neon/ring are coloured by definition (ink falls back to brand); duo = brand, then accent. */
function tintKeys(s: ThemeConfig['shadow']): [TintKey, TintKey] {
  if (s.tint === 'duo') return ['brand', 'accent'];
  const coloured = s.style === 'glow' || s.style === 'neon' || s.style === 'ring';
  const t: TintKey = coloured && s.tint === 'ink' ? 'brand' : s.tint;
  return [t, t];
}

const TINT_VAR: Record<ThemeTarget, Record<TintKey, string>> = {
  admin: { ink: '--cw-ink', brand: '--color-primary', accent: '--cw-accent', danger: '--cw-deal' },
  storefront: { ink: '--color-ink', brand: '--color-primary', accent: '--color-accent', danger: '--color-deal' },
};

function shadowCss(s: ThemeConfig['shadow'], target: ThemeTarget): string {
  const [rest, hover] = tintKeys(s).map((k) => TINT_VAR[target][k]) as [string, string];
  if (target === 'admin') {
    const col = (v: string) => (a: number) => (a >= 1 ? `var(${v})` : `color-mix(in srgb, var(${v}) ${Math.round(a * 100)}%, transparent)`);
    const t = shadowTokens(s, col(rest), col(hover));
    return t ? `:root:root{--cw-shadow-card:${t.card};--cw-shadow-card-hover:${t.hover};--cw-shadow-cta:${t.cta}}` : '';
  }
  const col = (v: string) => (a: number) => (a >= 1 ? `hsl(var(${v}))` : `hsl(var(${v}) / ${a})`);
  const t = shadowTokens(s, col(rest), col(hover));
  return t ? `:root:root{--shadow-card:${t.card};--shadow-card-hover:${t.hover};--shadow-cta:${t.cta}}` : '';
}

/** Concrete card/hover/button shadows for one mode, for the editor's scoped preview (hex/rgba). */
export function previewShadows(cfg: ThemeConfig, mode: 'light' | 'dark'): ShadowTokens {
  const t = deriveTokens(cfg[mode], mode);
  const hexOf = (k: TintKey) => ({ ink: t.ink, brand: t.primary, accent: t.accent, danger: t.deal })[k];
  const col = (hex: string) => {
    const [r, g, b] = hexToRgb(hex);
    return (a: number) => (a >= 1 ? hex : `rgba(${r},${g},${b},${a})`);
  };
  const [rest, hover] = tintKeys(cfg.shadow).map(hexOf) as [string, string];
  return shadowTokens(cfg.shadow, col(rest), col(hover)) ?? { card: `4px 4px 0 ${rest}`, hover: `8px 8px 0 ${hover}`, cta: `3px 3px 0 ${rest}` };
}

/** Outline colour one mode resolves to (honours the border colour setting). */
export function previewOutline(cfg: ThemeConfig, mode: 'light' | 'dark'): string {
  return cfg.border.color === 'default' ? deriveTokens(cfg[mode], mode).outline : outlineColour(cfg[mode], mode, cfg.border.color);
}

// ---- preview helpers (editor swatches, look specimens, the scoped preview) -------------------
// One source of truth for "what does this option look like at small size", so the editor never
// keeps its own copy of these tables.

export const PREVIEW_RADIUS: Record<RadiusKind, string> = {
  square: '0px',
  micro: '2px',
  sharp: '4px',
  soft: '12px',
  round: '22px',
  pill: '30px',
  mega: '40px',
};
export const PREVIEW_BORDER_WIDTH: Record<BorderWidth, string> = { none: '0px', thin: '1px', standard: '2px', bold: '3px', heavy: '4px' };
export const PREVIEW_BUTTON_RADIUS: Record<ButtonShape, string> = {
  default: '9999px',
  square: '4px',
  rounded: '12px',
  squircle: '15px',
  pill: '9999px',
  leaf: '16px 4px',
  tab: '12px 12px 3px 3px',
};
export const PREVIEW_CARD_RADIUS: Record<CardShape, string | null> = {
  default: null,
  leaf: '28px 6px',
  notch: '20px 3px 20px 20px',
  top: '20px 20px 5px 5px',
};

/** CSS `background` (shorthand) for a card in one mode: fill colour, pattern and accent bar. */
export function previewCardBackground(cfg: ThemeConfig, mode: 'light' | 'dark'): string {
  const p = cfg[mode];
  const line = mix(p.surface, p.text, 0.1);
  const layers: string[] = [];
  const bar = { none: '', top: 'top / 100% 4px', bottom: 'bottom / 100% 4px', left: 'left / 4px 100%' }[cfg.card.accent];
  if (bar) layers.push(`linear-gradient(${p.primary}, ${p.primary}) ${bar} no-repeat`);
  const pattern: Partial<Record<CardFill, string>> = {
    gradient: `linear-gradient(160deg, ${p.surface} 35%, ${mix(p.surface, p.primary, 0.16)})`,
    dots: `radial-gradient(${mix(p.surface, p.text, 0.14)} 1px, transparent 1.3px) 0 0 / 14px 14px`,
    stripes: `repeating-linear-gradient(135deg, ${mix(p.surface, p.primary, 0.08)} 0 8px, transparent 8px 16px)`,
    grid: `linear-gradient(${line} 1px, transparent 1px) 0 0 / 18px 18px, linear-gradient(90deg, ${line} 1px, transparent 1px) 0 0 / 18px 18px`,
  };
  const pat = pattern[cfg.card.fill];
  if (pat) layers.push(pat);
  const colour =
    {
      default: p.surface,
      tinted: mix(p.surface, p.primary, 0.08),
      accent: mix(p.surface, p.accent, 0.1),
      muted: mix(p.surface, p.text, 0.06),
      gradient: p.surface,
      glass: `${p.surface}9e`,
      outline: 'transparent',
      dots: p.surface,
      stripes: p.surface,
      grid: p.surface,
    }[cfg.card.fill] ?? p.surface;
  return [...layers, colour].join(', ');
}

/** Inline style for the main call-to-action button in one mode (fill, label colour, frame, label style). */
export function previewButtonStyle(cfg: ThemeConfig, mode: 'light' | 'dark'): Record<string, string> {
  const p = cfg[mode];
  const t = deriveTokens(p, mode);
  const fills: Record<ButtonFill, Record<string, string>> = {
    default: { background: `linear-gradient(180deg, ${t.accent}, ${t.accent2})`, color: t.onAccent },
    solid: { background: t.accent, color: t.onAccent },
    brand: { background: t.primary, color: t.onPrimary },
    soft: { background: mix(p.surface, p.accent, 0.24), color: p.text },
    outline: { background: 'transparent', color: p.text, boxShadow: `inset 0 0 0 2px ${p.primary}` },
    ghost: { background: 'transparent', color: p.primary, boxShadow: 'none', borderColor: 'transparent' },
    glass: { background: `${p.surface}8c`, color: p.text, backdropFilter: 'blur(10px)' },
    inverse: { background: p.text, color: p.surface },
  };
  const text: Record<ButtonText, Record<string, string>> = {
    default: {},
    upper: { textTransform: 'uppercase', letterSpacing: '0.06em' },
    bold: { fontWeight: '700' },
    wide: { letterSpacing: '0.12em' },
    smallcaps: { fontVariantCaps: 'all-small-caps', letterSpacing: '0.05em' },
  };
  return { ...fills[cfg.button.fill], ...text[cfg.button.text], borderRadius: PREVIEW_BUTTON_RADIUS[cfg.button.shape] };
}
// ---- border colour, cards, buttons, type tuning ----------------------------------------------

function outlineColour(p: ThemePalette, mode: 'light' | 'dark', kind: BorderColor): string {
  if (kind === 'subtle') return mix(p.surface, p.text, mode === 'dark' ? 0.24 : 0.16);
  if (kind === 'strong') return p.text;
  if (kind === 'muted') return mix(p.text, p.background, 0.45);
  if (kind === 'accent') return p.accent;
  return p.primary; // brand
}

function borderColourCss(cfg: ThemeConfig, target: ThemeTarget): string {
  const kind = cfg.border.color;
  if (kind === 'default') return '';
  const lightC = outlineColour(cfg.light, 'light', kind);
  const darkC = outlineColour(cfg.dark, 'dark', kind);
  if (target === 'admin') {
    return (
      `:root:root[data-theme='catalogLight']{--cw-outline:${hsl(lightC)}}` +
      `:root:root[data-theme='catalogDark']{--cw-outline:${hsl(darkC)}}`
    );
  }
  return `:root:root{--color-outline:${toTriple(lightC)}}:root:root[data-theme="dark"]{--color-outline:${toTriple(darkC)}}`;
}

/** Selectors and colour expressions for the card and button rules, per app. */
const PART = {
  storefront: {
    card: '.shadow-card',
    // Only cards that are already interactive (they carry the hover-shadow utility).
    hoverCard: `.hover${String.fromCharCode(92)}:shadow-card-hover`,
    hoverShadow: 'var(--shadow-card-hover)',
    restShadow: 'var(--shadow-card)',
    ctaShadow: 'var(--shadow-cta)',
    move: (y: number, scale = 1, rot = 0) => `translate:0 ${y}px;scale:${scale};rotate:${rot}deg`,
    surface: 'hsl(var(--color-surface))',
    primary: 'hsl(var(--color-primary))',
    onPrimary: 'hsl(var(--color-on-primary))',
    accent: 'hsl(var(--color-accent))',
    text: 'hsl(var(--color-on-surface))',
    cta: '.bg-cta-gradient',
    ctaShape: '.bg-cta-gradient,button.shadow-cta,a.shadow-cta',
  },
  admin: {
    card: '.data-card,.stat-card',
    hoverCard: 'a.stat-card',
    hoverShadow: 'var(--cw-shadow-card-hover, 6px 6px 0 var(--cw-ink))',
    restShadow: 'var(--cw-shadow-card, 4px 4px 0 var(--cw-ink))',
    ctaShadow: 'var(--cw-shadow-cta, 3px 3px 0 var(--cw-ink))',
    move: (y: number, scale = 1, rot = 0) => `transform:translateY(${y}px) scale(${scale}) rotate(${rot}deg)`,
    surface: 'var(--color-base-100)',
    primary: 'var(--color-primary)',
    onPrimary: 'var(--color-primary-content)',
    accent: 'var(--cw-accent)',
    text: 'var(--color-base-content)',
    cta: '.btn.btn-primary',
    ctaShape: '.btn:not(.btn-ghost):not(.btn-link):not(.btn-square):not(.btn-circle)',
  },
} as const;

const cm = (a: string, pct: number, b: string) => `color-mix(in srgb,${a} ${pct}%,${b})`;
/** `a,b` selector list with `:hover` on each item. */
const onHover = (list: string) =>
  list
    .split(',')
    .map((x) => `${x}:hover`)
    .join(',');

interface BgLayer {
  readonly images: readonly string[];
  readonly size: string;
  readonly pos: string;
  readonly repeat: string;
}

function cardCss(c: ThemeConfig['card'], target: ThemeTarget): string {
  const p = PART[target];
  const out: string[] = [];
  // Background: one colour plus image layers (accent bar first so it paints on top, then pattern).
  const fillColour: Partial<Record<CardFill, string>> = {
    tinted: cm(p.surface, 92, p.primary),
    accent: cm(p.surface, 90, p.accent),
    muted: cm(p.surface, 94, p.text),
    glass: cm(p.surface, 62, 'transparent'),
    outline: 'transparent',
  };
  const layers: BgLayer[] = [];
  if (c.accent !== 'none') {
    const bar = { top: ['100% 4px', 'top'], bottom: ['100% 4px', 'bottom'], left: ['4px 100%', 'left'] } as const;
    const [size, pos] = bar[c.accent];
    layers.push({ images: [`linear-gradient(${p.primary},${p.primary})`], size, pos, repeat: 'no-repeat' });
  }
  const line = cm(p.text, 8, 'transparent');
  const patterns: Partial<Record<CardFill, BgLayer>> = {
    gradient: { images: [`linear-gradient(160deg,${p.surface} 35%,${cm(p.surface, 84, p.primary)})`], size: 'auto', pos: '0 0', repeat: 'no-repeat' },
    dots: { images: [`radial-gradient(${cm(p.text, 12, 'transparent')} 1px,transparent 1.3px)`], size: '14px 14px', pos: '0 0', repeat: 'repeat' },
    stripes: { images: [`repeating-linear-gradient(135deg,${cm(p.primary, 7, 'transparent')} 0 8px,transparent 8px 16px)`], size: 'auto', pos: '0 0', repeat: 'repeat' },
    grid: { images: [`linear-gradient(${line} 1px,transparent 1px)`, `linear-gradient(90deg,${line} 1px,transparent 1px)`], size: '18px 18px', pos: '0 0', repeat: 'repeat' },
  };
  const pattern = patterns[c.fill];
  if (pattern) layers.push(pattern);
  const bg: string[] = [];
  const colour = fillColour[c.fill] ?? (layers.length ? p.surface : '');
  if (colour) bg.push(`background-color:${colour}`);
  if (layers.length) {
    const per = (key: 'size' | 'pos' | 'repeat') => layers.flatMap((l) => l.images.map(() => l[key])).join(',');
    bg.push(
      `background-image:${layers.flatMap((l) => l.images).join(',')}`,
      `background-size:${per('size')}`,
      `background-position:${per('pos')}`,
      `background-repeat:${per('repeat')}`,
    );
  }
  if (c.fill === 'glass') bg.push('backdrop-filter:blur(14px) saturate(140%)', '-webkit-backdrop-filter:blur(14px) saturate(140%)');
  if (c.fill === 'outline') bg.push('box-shadow:none');
  if (bg.length) out.push(`${p.card}{${bg.join(';')}}`);

  const shapes: Partial<Record<CardShape, string>> = {
    leaf: '1.75rem 0.35rem',
    notch: '1.25rem 0.2rem 1.25rem 1.25rem',
    top: '1.25rem 1.25rem 0.3rem 0.3rem',
  };
  const shape = shapes[c.shape];
  if (shape) out.push(`${p.card}{border-radius:${shape}}`);

  const rest = c.fill === 'outline' ? 'none' : p.restShadow;
  const glowRing = `0 0 0 2px ${p.primary},0 12px 32px -6px ${cm(p.primary, 45, 'transparent')}`;
  const rules: Partial<Record<CardHover, string>> = {
    none: `${p.move(0)};box-shadow:${rest}`,
    lift: `${p.move(-6)};box-shadow:${p.hoverShadow}`,
    float: `${p.move(-12, 1.01)};box-shadow:${p.hoverShadow},0 24px 40px -18px ${cm(p.text, 35, 'transparent')}`,
    glow: `${p.move(-2)};box-shadow:${glowRing}`,
    scale: `${p.move(0, 1.03)};box-shadow:${p.hoverShadow}`,
    pop: `${p.move(-4, 1.05)};box-shadow:${p.hoverShadow}`,
    tilt: `${p.move(-3, 1.01, -1.2)};box-shadow:${p.hoverShadow}`,
    press: `${p.move(2, 0.99)};box-shadow:none`,
    border: `${p.move(0)};box-shadow:${rest === 'none' ? '' : `${rest},`}0 0 0 2px ${p.primary}`,
    dim: `${p.move(0)};filter:brightness(.96)`,
  };
  const rule = rules[c.hover];
  if (rule) {
    out.push(`${p.hoverCard}{transition:translate .25s ease,scale .25s ease,rotate .25s ease,transform .25s ease,box-shadow .25s ease,filter .25s ease}`);
    out.push(`${p.hoverCard}:hover{${rule}}`);
  }
  return out.join('');
}

function buttonCss(b: ThemeConfig['button'], target: ThemeTarget): string {
  const p = PART[target];
  const out: string[] = [];
  const radius = {
    default: '',
    square: '4px',
    rounded: '0.75rem',
    squircle: '0.95rem',
    pill: '9999px',
    leaf: '1rem 0.25rem',
    tab: '0.75rem 0.75rem 0.2rem 0.2rem',
  }[b.shape];
  if (radius) out.push(`${p.ctaShape}{border-radius:${radius}}`);
  const fills: Partial<Record<ButtonFill, string>> = {
    solid: `background-image:none;background-color:${p.accent}`,
    brand: `background-image:none;background-color:${p.primary};color:${p.onPrimary}`,
    soft: `background-image:none;background-color:${cm(p.accent, 24, p.surface)};color:${p.text}`,
    outline: `background-image:none;background-color:transparent;color:${p.text};border:2px solid ${p.primary};box-shadow:none`,
    ghost: `background-image:none;background-color:transparent;color:${p.primary};border-color:transparent;box-shadow:none`,
    glass: `background-image:none;background-color:${cm(p.surface, 55, 'transparent')};color:${p.text};border:1px solid ${cm(p.text, 22, 'transparent')};backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px)`,
    inverse: `background-image:none;background-color:${p.text};color:${p.surface}`,
  };
  const fill = fills[b.fill];
  if (fill) out.push(`${p.cta}{${fill}}`);
  const text = {
    default: '',
    upper: 'text-transform:uppercase;letter-spacing:.06em',
    bold: 'font-weight:700;letter-spacing:.01em',
    wide: 'letter-spacing:.12em',
    smallcaps: 'font-variant-caps:all-small-caps;letter-spacing:.05em',
  }[b.text];
  if (text) out.push(`${p.ctaShape}{${text}}`);
  const hoverRules: Partial<Record<ButtonHover, string>> = {
    none: `${p.move(0)};box-shadow:${p.ctaShadow}`,
    lift: p.move(-2),
    grow: p.move(0, 1.04),
    glow: `box-shadow:0 0 0 3px ${cm(p.accent, 35, 'transparent')},0 6px 18px ${cm(p.accent, 45, 'transparent')}`,
    darken: 'filter:brightness(.92)',
  };
  const h = hoverRules[b.hover];
  if (h) {
    out.push(`${p.ctaShape}{transition:translate .2s ease,scale .2s ease,transform .2s ease,box-shadow .2s ease,filter .2s ease}`);
    out.push(`${onHover(p.ctaShape)}{${h}}`);
  }
  return out.join('');
}

function typeCss(f: ThemeConfig['fonts']): string {
  const out: string[] = [];
  const heading = 'h1,h2,h3,.font-display';
  const weight = { default: '', medium: '500', semibold: '600', bold: '700' }[f.headingWeight];
  if (weight) out.push(`${heading}{font-weight:${weight}}`);
  if (f.tracking !== 'default') out.push(`${heading}{letter-spacing:${f.tracking === 'tight' ? '-0.025em' : '0.04em'}}`);
  if (f.lineHeight !== 'default') out.push(`body{line-height:${f.lineHeight === 'compact' ? 1.4 : 1.75}}`);
  return out.join('');
}

/**
 * Outline thickness. Only the 2px utilities (`border-2`, `border-t-2`...) are rescaled: the 1px
 * hairlines stay hairlines, and unlayered overrides of plain `.border` would beat combos such as
 * `border border-b-0`.
 */
/** Line style of card outlines (inputs and dividers keep solid lines). */
function borderStyleCss(style: BorderStyle, target: ThemeTarget): string {
  return style === 'solid' ? '' : `${PART[target].card}{border-style:${style}}`;
}

function borderWidthCss(width: BorderWidth, target: ThemeTarget): string {
  if (width === 'standard') return '';
  const px = { none: '0px', thin: '1px', standard: '2px', bold: '3px', heavy: '4px' }[width];
  if (target === 'admin') {
    // daisyUI's --border also outlines inputs; never take it to zero or fields vanish.
    return `:root:root{--cw-bw:${px};--border:${width === 'none' ? '1px' : width === 'heavy' ? '3px' : px}}`;
  }
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
    borderColourCss(cfg, target),
    borderStyleCss(cfg.border.style, target),
    shadowCss(cfg.shadow, target),
    styleCss(cfg.style.id, cfg, target),
    // Explicit card/button/type choices come after the design style so they win over it.
    cardCss(cfg.card, target),
    buttonCss(cfg.button, target),
    typeCss(cfg.fonts),
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
