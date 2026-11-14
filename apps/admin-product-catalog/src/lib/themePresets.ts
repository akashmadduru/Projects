import { DEFAULT_THEME, type RadiusKind, type ShadowStyle, type ThemeConfig } from '@/lib/themeEngine';

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

function preset(
  id: string,
  label: string,
  bestFor: string,
  c: Pick<ThemeConfig, 'light' | 'dark' | 'fonts'> & { radius: RadiusKind },
): ThemePreset {
  const { radius, ...rest } = c;
  // Each look gets a shadow that suits its corners; the editor can change it independently.
  const shadow: ShadowStyle = radius === 'sharp' ? 'crisp' : radius === 'round' ? 'lifted' : 'soft';
  return {
    id,
    label,
    bestFor,
    config: {
      ...base,
      preset: id,
      brand: base.brand,
      customCss: '',
      ...rest,
      border: { radius, width: 'standard' },
      shadow: { style: shadow },
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
];

export function findPreset(id: string): ThemePreset | undefined {
  return THEME_PRESETS.find((p) => p.id === id);
}
