import { DEFAULT_THEME, type RadiusKind, type ShadowStyle, type ThemeConfig, type ThemePalette } from '@/lib/themeEngine';

/**
 * Curated starting points. Each pairs a light and a dark palette, a font pairing and a corner
 * style, and every text/background pair is chosen to clear WCAG AA (4.5:1) - the editor re-checks
 * after any manual change. `bestFor` is shown on the card so merchants pick by business, not hue.
 */
export interface ThemePreset {
  readonly id: string;
  readonly label: string;
  readonly bestFor: string;
  readonly config: ThemeConfig;
}

const base = DEFAULT_THEME;

interface PresetSpec {
  light: ThemePalette;
  dark: ThemePalette;
  fonts: { display: string; body: string } & Partial<ThemeConfig['fonts']>;
  radius: RadiusKind;
  /** Optional refinements; anything omitted stays built-in. */
  border?: Partial<ThemeConfig['border']>;
  shadow?: Partial<ThemeConfig['shadow']>;
  card?: Partial<ThemeConfig['card']>;
  button?: Partial<ThemeConfig['button']>;
}

function preset(id: string, label: string, bestFor: string, c: PresetSpec): ThemePreset {
  // Each look gets a shadow that suits its corners unless it names one; the editor can change it.
  const shadow: ShadowStyle =
    c.radius === 'sharp' || c.radius === 'square' ? 'crisp' : c.radius === 'round' || c.radius === 'pill' ? 'lifted' : 'soft';
  return {
    id,
    label,
    bestFor,
    config: {
      ...base,
      preset: id,
      brand: base.brand,
      customCss: '',
      light: c.light,
      dark: c.dark,
      fonts: { ...base.fonts, ...c.fonts },
      border: { ...base.border, width: 'standard', ...c.border, radius: c.radius },
      shadow: { ...base.shadow, style: shadow, ...c.shadow },
      card: { ...base.card, ...c.card },
      button: { ...base.button, ...c.button },
    },
  };
}

export const THEME_PRESETS: readonly ThemePreset[] = [
  {
    id: 'atomic',
    label: 'Space-Age Atomic',
    bestFor: 'The built-in look: playful, retro, general merchandise',
    config: base,
  },
  preset('midnight-indigo', 'Midnight Indigo', 'Electronics, gadgets, software and tech accessories', {
    light: {
      primary: '#4338ca',
      accent: '#f59e0b',
      background: '#f4f5fb',
      surface: '#ffffff',
      text: '#1e1b4b',
      danger: '#c81e4a',
    },
    dark: {
      primary: '#a5b4fc',
      accent: '#fbbf24',
      background: '#0b0d1f',
      surface: '#14172e',
      text: '#eef0ff',
      danger: '#fb7185',
    },
    radius: 'soft',
    fonts: { display: 'Inter', body: 'Inter' },
  }),
  preset('forest-market', 'Forest Market', 'Grocery, organic, wellness and sustainable goods', {
    light: {
      primary: '#2f6b3f',
      accent: '#d9733f',
      background: '#f3f1e7',
      surface: '#fdfcf6',
      text: '#1f2a21',
      danger: '#b3261e',
    },
    dark: {
      primary: '#8fd19e',
      accent: '#f09a6a',
      background: '#0f1a13',
      surface: '#17251c',
      text: '#eef4e8',
      danger: '#f2766f',
    },
    radius: 'round',
    fonts: { display: 'Nunito', body: 'Nunito' },
  }),
  preset('crimson-luxe', 'Crimson Luxe', 'Fashion, jewellery, premium and gifting', {
    light: {
      primary: '#8c1c3b',
      accent: '#b8901f',
      background: '#faf5ef',
      surface: '#fffdf9',
      text: '#2a1a1f',
      danger: '#b3122f',
    },
    dark: {
      primary: '#f08aa6',
      accent: '#e3bd55',
      background: '#17090d',
      surface: '#240f15',
      text: '#fbefe9',
      danger: '#ff7a92',
    },
    radius: 'sharp',
    fonts: { display: 'Playfair Display', body: 'DM Sans' },
  }),
  preset('ocean-breeze', 'Ocean Breeze', 'Home, travel, appliances and everyday essentials', {
    light: {
      primary: '#0b6fa4',
      accent: '#ff7a59',
      background: '#eef6fa',
      surface: '#ffffff',
      text: '#10303f',
      danger: '#c62828',
    },
    dark: {
      primary: '#6cc4f0',
      accent: '#ff9c80',
      background: '#08141c',
      surface: '#0f212c',
      text: '#e8f4fa',
      danger: '#ff7b7b',
    },
    radius: 'soft',
    fonts: { display: 'DM Sans', body: 'DM Sans' },
  }),
  preset('sunset-citrus', 'Sunset Citrus', 'Flash sales, deals, food and high-energy promotions', {
    light: {
      primary: '#b83a0a',
      accent: '#0f9d8a',
      background: '#fff4e6',
      surface: '#fffaf2',
      text: '#35190c',
      danger: '#b3122f',
    },
    dark: {
      primary: '#ff9a62',
      accent: '#3ed1bd',
      background: '#1a0d06',
      surface: '#271409',
      text: '#fff0e1',
      danger: '#ff7a8a',
    },
    radius: 'round',
    fonts: { display: 'Poppins', body: 'Poppins' },
  }),
  preset('mono-ink', 'Mono Ink', 'Minimal, editorial, design-led and premium basics', {
    light: {
      primary: '#111827',
      accent: '#65a30d',
      background: '#f5f5f4',
      surface: '#ffffff',
      text: '#111827',
      danger: '#b91c1c',
    },
    dark: {
      primary: '#f5f5f4',
      accent: '#a3e635',
      background: '#0a0a0a',
      surface: '#141414',
      text: '#fafafa',
      danger: '#f87171',
    },
    radius: 'sharp',
    fonts: { display: 'Space Grotesk', body: 'Inter' },
  }),
  preset('lavender-bloom', 'Lavender Bloom', 'Beauty, lifestyle, kids and gifting', {
    light: {
      primary: '#6d3fc0',
      accent: '#e0368a',
      background: '#f6f1fc',
      surface: '#fffcff',
      text: '#271a3d',
      danger: '#b3122f',
    },
    dark: {
      primary: '#c4a4ff',
      accent: '#ff7eb9',
      background: '#130b21',
      surface: '#1e1232',
      text: '#f4ecff',
      danger: '#ff7a92',
    },
    radius: 'round',
    fonts: { display: 'Manrope', body: 'Manrope' },
  }),
  preset('rose-quartz', 'Rose Quartz', 'Skincare, cosmetics and self-care', {
    light: { primary: '#a3294f', accent: '#c98a2b', background: '#fdf2f4', surface: '#fffafb', text: '#3a1d27', danger: '#b3122f' },
    dark: { primary: '#f4a3bb', accent: '#e8b95c', background: '#1c0d13', surface: '#2a141d', text: '#fbeaf0', danger: '#ff7a92' },
    radius: 'round',
    fonts: { display: 'Raleway', body: 'Lora' },
  }),
  preset('slate-copper', 'Slate & Copper', 'Furniture, home decor and workshop tools', {
    light: { primary: '#334155', accent: '#b45f2b', background: '#f1f0ec', surface: '#fbfaf8', text: '#1e2733', danger: '#b42318' },
    dark: { primary: '#9db4cf', accent: '#e39a63', background: '#0e1218', surface: '#171d26', text: '#e9edf2', danger: '#f3766b' },
    radius: 'soft',
    fonts: { display: 'Fraunces', body: 'Work Sans' },
  }),
  preset('emerald-night', 'Emerald Night', 'Health, wellness, finance and subscriptions', {
    light: { primary: '#047857', accent: '#d97706', background: '#effaf5', surface: '#ffffff', text: '#0b2a20', danger: '#b91c1c' },
    dark: { primary: '#5eead4', accent: '#fbbf24', background: '#06150f', surface: '#0d231a', text: '#e6f7ef', danger: '#fb7185' },
    radius: 'soft',
    fonts: { display: 'Sora', body: 'Plus Jakarta Sans' },
  }),
  preset('berry-pop', 'Berry Pop', 'Toys, kids, party and novelty', {
    light: { primary: '#a21caf', accent: '#d4a000', background: '#fdf4ff', surface: '#ffffff', text: '#3b0a45', danger: '#be123c' },
    dark: { primary: '#f0abfc', accent: '#fde047', background: '#16051a', surface: '#240a2b', text: '#fbe9ff', danger: '#fb7185' },
    radius: 'round',
    fonts: { display: 'Outfit', body: 'Rubik' },
  }),
  preset('desert-sand', 'Desert Sand', 'Handcrafted goods, ceramics and artisan decor', {
    light: { primary: '#9a4b1f', accent: '#2f7f79', background: '#f7efe3', surface: '#fffaf2', text: '#33231a', danger: '#b3261e' },
    dark: { primary: '#e8a778', accent: '#6fc3bb', background: '#17100b', surface: '#241a13', text: '#f6ebdd', danger: '#f2786f' },
    radius: 'soft',
    fonts: { display: 'Cormorant Garamond', body: 'Karla' },
  }),
  preset('arctic-mint', 'Arctic Mint', 'Pharmacy, hygiene and clean-living products', {
    light: { primary: '#0e7490', accent: '#e11d48', background: '#edf8fb', surface: '#ffffff', text: '#0c2a33', danger: '#be123c' },
    dark: { primary: '#67e8f9', accent: '#fb7185', background: '#061519', surface: '#0c2329', text: '#e4f6fa', danger: '#ff8fa0' },
    radius: 'soft',
    fonts: { display: 'Montserrat', body: 'Source Sans 3' },
  }),
  preset('graphite-gold', 'Graphite & Gold', 'Watches, accessories and premium gifting', {
    light: { primary: '#292524', accent: '#a16207', background: '#f6f3ee', surface: '#fffdf9', text: '#1c1917', danger: '#b91c1c' },
    dark: { primary: '#e7c873', accent: '#d6a43a', background: '#0c0a09', surface: '#1a1714', text: '#f5f0e6', danger: '#f87171' },
    radius: 'sharp',
    fonts: { display: 'Playfair Display', body: 'Inter' },
  }),
  preset('cobalt-sport', 'Cobalt Sport', 'Sportswear, outdoors and fitness gear', {
    light: { primary: '#1d4ed8', accent: '#ea580c', background: '#eff4ff', surface: '#ffffff', text: '#0f1b3d', danger: '#b91c1c' },
    dark: { primary: '#93b4ff', accent: '#fb923c', background: '#060b1c', surface: '#0e1630', text: '#e8eeff', danger: '#fb7185' },
    radius: 'sharp',
    fonts: { display: 'Oswald', body: 'Source Sans 3' },
  }),
  // ---- refined looks that also tune borders, shadows, cards and buttons ----
  preset('silk-minimal', 'Silk Minimal', 'Premium DTC brands, home goods and apparel', {
    light: { primary: '#1f2937', accent: '#b4532a', background: '#f7f6f3', surface: '#ffffff', text: '#1f2937', danger: '#b42318' },
    dark: { primary: '#e5e7eb', accent: '#f0a37b', background: '#0f1012', surface: '#18191c', text: '#f3f4f6', danger: '#f97066' },
    radius: 'soft',
    fonts: { display: 'Geist', body: 'Geist', headingWeight: 'semibold', tracking: 'tight' },
    border: { width: 'thin', color: 'subtle' },
    shadow: { style: 'layered' },
    card: { hover: 'lift' },
    button: { shape: 'rounded', fill: 'solid' },
  }),
  preset('nordic-calm', 'Nordic Calm', 'Furniture, lighting and Scandinavian living', {
    light: { primary: '#365a6b', accent: '#c26a45', background: '#f2f1ee', surface: '#fbfbf9', text: '#22313a', danger: '#b42318' },
    dark: { primary: '#9cc3d5', accent: '#e6a07e', background: '#0f1518', surface: '#172024', text: '#e9eef0', danger: '#f97066' },
    radius: 'round',
    fonts: { display: 'Figtree', body: 'Figtree', headingWeight: 'semibold' },
    border: { width: 'none' },
    shadow: { style: 'soft', intensity: 'light' },
    card: { hover: 'lift', fill: 'tinted' },
    button: { shape: 'pill', fill: 'solid' },
  }),
  preset('neon-arcade', 'Neon Arcade', 'Gaming, gadgets, energy drinks and streetwear drops', {
    light: { primary: '#5b21b6', accent: '#0e7490', background: '#f5f3ff', surface: '#ffffff', text: '#1e1033', danger: '#be123c' },
    dark: { primary: '#c084fc', accent: '#22d3ee', background: '#0a0612', surface: '#140d22', text: '#f3e8ff', danger: '#fb7185' },
    radius: 'round',
    fonts: { display: 'Unbounded', body: 'Figtree' },
    border: { width: 'thin', color: 'brand' },
    shadow: { style: 'glow', tint: 'brand' },
    card: { hover: 'glow' },
    button: { shape: 'pill', text: 'upper' },
  }),
  preset('paper-press', 'Paper Press', 'Books, stationery, magazines and indie publishers', {
    light: { primary: '#7c2d12', accent: '#1d4ed8', background: '#f8f4ec', surface: '#fffdf8', text: '#231a12', danger: '#b42318' },
    dark: { primary: '#fdba8c', accent: '#93b4ff', background: '#14100b', surface: '#1f1912', text: '#f6efe3', danger: '#f97066' },
    radius: 'square',
    fonts: { display: 'Newsreader', body: 'Public Sans', headingWeight: 'semibold' },
    border: { width: 'thin', color: 'strong' },
    shadow: { style: 'none' },
    card: { hover: 'none', fill: 'outline' },
    button: { shape: 'square', fill: 'outline', text: 'upper' },
  }),
  preset('couture-noir', 'Couture Noir', 'Luxury fashion, fragrance and fine jewellery', {
    light: { primary: '#111111', accent: '#8a6a1f', background: '#f6f4f0', surface: '#fffefc', text: '#141414', danger: '#a61b1b' },
    dark: { primary: '#f1e6c8', accent: '#d8b45a', background: '#08080a', surface: '#121214', text: '#f4f1ea', danger: '#f87171' },
    radius: 'square',
    fonts: { display: 'Bodoni Moda', body: 'Hanken Grotesk', headingWeight: 'medium', tracking: 'wide' },
    border: { width: 'thin', color: 'subtle' },
    shadow: { style: 'none' },
    card: { hover: 'scale' },
    button: { shape: 'square', fill: 'solid', text: 'upper' },
  }),
  preset('peach-fuzz', 'Peach Fuzz', 'Bakery, cafes, candles and cosy gifting', {
    light: { primary: '#9a3f22', accent: '#2f7d6d', background: '#fff3ec', surface: '#fffaf6', text: '#3a1f15', danger: '#b42318' },
    dark: { primary: '#ffb592', accent: '#7fd1bf', background: '#1a0f0a', surface: '#26170f', text: '#fdeee5', danger: '#f97066' },
    radius: 'pill',
    fonts: { display: 'Crimson Pro', body: 'Nunito Sans', headingWeight: 'semibold' },
    border: { width: 'none' },
    shadow: { style: 'soft', tint: 'brand' },
    card: { hover: 'lift', fill: 'gradient' },
    button: { shape: 'pill', fill: 'soft' },
  }),
  preset('comic-pop', 'Comic Pop', 'Toys, collectibles, snacks and fun merch', {
    light: { primary: '#1d4ed8', accent: '#c2410c', background: '#fff8e1', surface: '#ffffff', text: '#111827', danger: '#be123c' },
    dark: { primary: '#93b4ff', accent: '#fb923c', background: '#0b1020', surface: '#141b33', text: '#f8fafc', danger: '#fb7185' },
    radius: 'round',
    fonts: { display: 'Gabarito', body: 'Nunito Sans', headingWeight: 'bold' },
    border: { width: 'bold', color: 'strong' },
    shadow: { style: 'long' },
    card: { hover: 'lift' },
    button: { shape: 'pill', text: 'upper' },
  }),
  preset('ocean-glass', 'Ocean Glass', 'Travel, swimwear, water sports and resorts', {
    light: { primary: '#075985', accent: '#c2410c', background: '#ecf7fc', surface: '#ffffff', text: '#0c2a3a', danger: '#b91c1c' },
    dark: { primary: '#7dd3fc', accent: '#fdba74', background: '#04131c', surface: '#0a2230', text: '#e6f6fd', danger: '#fb7185' },
    radius: 'round',
    fonts: { display: 'Urbanist', body: 'Inter', headingWeight: 'bold', tracking: 'tight' },
    border: { width: 'thin', color: 'subtle' },
    shadow: { style: 'layered', tint: 'brand' },
    card: { hover: 'lift', fill: 'gradient' },
    button: { shape: 'pill', fill: 'solid' },
  }),
  preset('matcha', 'Matcha', 'Tea, coffee, plant shops and slow living', {
    light: { primary: '#3f6212', accent: '#9a3412', background: '#f4f6ec', surface: '#fcfdf7', text: '#1f2a12', danger: '#b42318' },
    dark: { primary: '#bef264', accent: '#fdba74', background: '#0d1207', surface: '#161e0d', text: '#f0f5e4', danger: '#f97066' },
    radius: 'round',
    fonts: { display: 'Literata', body: 'Albert Sans', headingWeight: 'semibold' },
    border: { width: 'thin', color: 'subtle' },
    shadow: { style: 'soft', intensity: 'light' },
    card: { hover: 'lift', fill: 'tinted' },
    button: { shape: 'pill', fill: 'solid' },
  }),
  preset('tech-slate', 'Tech Slate', 'Electronics, B2B, SaaS add-ons and developer gear', {
    light: { primary: '#2563eb', accent: '#0f766e', background: '#f6f7f9', surface: '#ffffff', text: '#0f172a', danger: '#b91c1c' },
    dark: { primary: '#93b4ff', accent: '#5eead4', background: '#0b0f17', surface: '#121826', text: '#e5e9f0', danger: '#fb7185' },
    radius: 'sharp',
    fonts: { display: 'Geist', body: 'Inter', headingWeight: 'semibold', tracking: 'tight' },
    border: { width: 'thin', color: 'subtle' },
    shadow: { style: 'layered', intensity: 'light' },
    card: { hover: 'glow' },
    button: { shape: 'rounded', fill: 'solid' },
  }),
  preset('sunrise-candy', 'Sunrise Candy', 'Beauty, cosmetics and Gen-Z lifestyle', {
    light: { primary: '#be185d', accent: '#6d28d9', background: '#fff1f6', surface: '#ffffff', text: '#3b0820', danger: '#b91c1c' },
    dark: { primary: '#f9a8d4', accent: '#c4b5fd', background: '#1a0712', surface: '#2a0d1e', text: '#fde7f1', danger: '#fb7185' },
    radius: 'pill',
    fonts: { display: 'Bricolage Grotesque', body: 'Instrument Sans', headingWeight: 'bold' },
    border: { width: 'none' },
    shadow: { style: 'glow', tint: 'accent', intensity: 'light' },
    card: { hover: 'scale', fill: 'gradient' },
    button: { shape: 'pill' },
  }),
  preset('heritage-library', 'Heritage Library', 'Wine, spirits, antiques and heritage brands', {
    light: { primary: '#5b1a1a', accent: '#8a6a1f', background: '#f5efe3', surface: '#fcf8f0', text: '#2a1b12', danger: '#a61b1b' },
    dark: { primary: '#f0a8a0', accent: '#e0bf6a', background: '#120c08', surface: '#1e150f', text: '#f4ecdf', danger: '#f87171' },
    radius: 'sharp',
    fonts: { display: 'EB Garamond', body: 'Mulish', headingWeight: 'semibold', tracking: 'wide', lineHeight: 'relaxed' },
    border: { width: 'thin', color: 'strong' },
    shadow: { style: 'soft', intensity: 'light' },
    card: { hover: 'lift' },
    button: { shape: 'square', fill: 'solid', text: 'upper' },
  }),
];

export function findPreset(id: string): ThemePreset | undefined {
  return THEME_PRESETS.find((p) => p.id === id);
}
