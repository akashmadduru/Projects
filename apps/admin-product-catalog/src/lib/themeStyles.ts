import {
  DEFAULT_THEME,
  type BorderWidth,
  type RadiusKind,
  type ShadowStyle,
  type StyleId,
  type ThemePalette,
} from '@/lib/themeEngine';

/**
 * Starter kits for the design languages. Selecting a style always turns on its runtime effects
 * (see STYLE_FX in themeEngine.ts); the kit below is the matching palette, fonts, corners, outline
 * and shadow, applied too unless the admin opts to keep their own.
 *
 * Palettes are [primary, accent, background, surface, text, danger]. Body text and button text for
 * every kit are asserted to meet WCAG AA in themeEngine.spec.ts.
 */
export interface StyleKit {
  readonly id: Exclude<StyleId, 'default'>;
  readonly label: string;
  /** One-line description of the design language. */
  readonly about: string;
  readonly bestFor: string;
  readonly light: ThemePalette;
  readonly dark: ThemePalette;
  readonly fonts: { display: string; body: string };
  readonly border: { radius: RadiusKind; width: BorderWidth };
  readonly shadow: ShadowStyle;
}

type P = readonly [string, string, string, string, string, string];

const pal = ([primary, accent, background, surface, text, danger]: P): ThemePalette => ({
  primary,
  accent,
  background,
  surface,
  text,
  danger,
});

function kit(
  id: StyleKit['id'],
  label: string,
  about: string,
  bestFor: string,
  light: P,
  dark: P,
  fonts: [string, string],
  radius: RadiusKind,
  width: BorderWidth,
  shadow: ShadowStyle,
): StyleKit {
  return {
    id,
    label,
    about,
    bestFor,
    light: pal(light),
    dark: pal(dark),
    fonts: { display: fonts[0], body: fonts[1] },
    border: { radius, width },
    shadow,
  };
}

export const STYLE_KITS: readonly StyleKit[] = [
  kit('minimalism', 'Minimalism', 'Restraint: monochrome, generous space, hairline outlines, no ornament.', 'Premium basics, portfolios, any catalogue that should feel calm',
    ['#171717', '#737373', '#fafafa', '#ffffff', '#171717', '#b91c1c'], ['#fafafa', '#a3a3a3', '#0a0a0a', '#111111', '#fafafa', '#f87171'], ['Inter', 'Inter'], 'sharp', 'thin', 'none'),
  kit('flat-design', 'Flat Design', 'No depth cues: solid colour blocks, crisp edges, zero shadows.', 'Fast, utilitarian storefronts and dashboards',
    ['#2563eb', '#f59e0b', '#eef2f7', '#ffffff', '#0f172a', '#dc2626'], ['#60a5fa', '#fbbf24', '#0b1220', '#131c2e', '#e8eefc', '#f87171'], ['Montserrat', 'Source Sans 3'], 'soft', 'thin', 'none'),
  kit('flat-2', 'Flat 2.0 (Semi-Flat)', 'Flat colour with a whisper of elevation so things still read as pressable.', 'General retail; the safe modern default',
    ['#0891b2', '#f97316', '#f0f7fa', '#ffffff', '#0b2a33', '#dc2626'], ['#67e8f9', '#fb923c', '#071419', '#0e222a', '#e6f6fa', '#fb7185'], ['DM Sans', 'DM Sans'], 'soft', 'thin', 'soft'),
  kit('material', 'Material Design', 'Layered paper with purposeful elevation and tonal colour.', 'Apps and marketplaces used on Android-heavy audiences',
    ['#6750a4', '#7d5260', '#fef7ff', '#ffffff', '#1c1b1f', '#b3261e'], ['#d0bcff', '#efb8c8', '#141218', '#1d1b20', '#e6e0e9', '#f2b8b5'], ['Work Sans', 'Work Sans'], 'soft', 'thin', 'soft'),
  kit('fluent', 'Fluent Design', 'Acrylic translucency, light depth and calm blues.', 'Business, productivity and B2B storefronts',
    ['#0f6cbd', '#8764b8', '#f3f3f3', '#fcfcfc', '#1b1b1b', '#c42b1c'], ['#4cc2ff', '#b4a0ff', '#202020', '#2b2b2b', '#ffffff', '#ff99a4'], ['Source Sans 3', 'Source Sans 3'], 'soft', 'thin', 'soft'),
  kit('hig', 'Human Interface Guidelines (HIG)', 'Clarity and deference: system blue, translucent bars, big friendly radii.', 'Consumer electronics and lifestyle brands',
    ['#007aff', '#ff9500', '#f2f2f7', '#ffffff', '#111111', '#d70015'], ['#0a84ff', '#ff9f0a', '#000000', '#1c1c1e', '#f5f5f7', '#ff6961'], ['Inter', 'Inter'], 'round', 'thin', 'soft'),
  kit('dark-mode', 'Dark Mode', 'Dark in both modes: low-glare surfaces with luminous accents.', 'Gaming, media, night-time browsing',
    ['#7c9cff', '#2dd4bf', '#0e1117', '#161b22', '#e6edf3', '#ff7b72'], ['#7c9cff', '#2dd4bf', '#0e1117', '#161b22', '#e6edf3', '#ff7b72'], ['Inter', 'Inter'], 'soft', 'thin', 'soft'),
  kit('skeuomorphism', 'Skeuomorphism', 'Real-world materials: gradients, bevels, embossed surfaces.', 'Craft, leather, audio gear, heritage brands',
    ['#8a5a2b', '#2e7d32', '#d9d2c3', '#efe9dc', '#2b2218', '#a61e1e'], ['#d9a066', '#7bc67e', '#1f1a14', '#2c251c', '#f1e7d6', '#f08080'], ['Fraunces', 'Karla'], 'soft', 'standard', 'lifted'),
  kit('neumorphism', 'Neumorphism (Soft UI)', 'Extruded, same-colour surfaces lit by a soft light and shadow pair.', 'Wellness, smart-home and calm product pages',
    ['#4f6bed', '#6d3fe0', '#e6e9ef', '#e6e9ef', '#2d3748', '#d33a4a'], ['#8ea2ff', '#b79cff', '#22262e', '#22262e', '#e6e9f0', '#ff7a8a'], ['Nunito', 'Nunito'], 'round', 'thin', 'none'),
  kit('glassmorphism', 'Glassmorphism', 'Frosted translucent panels floating over a colourful wash.', 'Modern lifestyle, fintech-style and fashion drops',
    ['#6d5dfc', '#ff6bd6', '#e8ecff', '#ffffff', '#1b1840', '#c2185b'], ['#a89bff', '#ff8be0', '#0b0820', '#17123a', '#f1eeff', '#ff7aa8'], ['Outfit', 'Inter'], 'round', 'thin', 'soft'),
  kit('claymorphism', 'Claymorphism', 'Inflated, tactile 3D-clay shapes with soft inner light.', 'Kids, toys, games and playful brands',
    ['#ff7a59', '#6a49f0', '#fff0e8', '#fff7f2', '#4a2c2a', '#d62f4a'], ['#ff9f86', '#a99bff', '#20120f', '#2c1b17', '#ffeee6', '#ff8099'], ['Nunito', 'Nunito'], 'round', 'thin', 'none'),
  kit('liquid-glass', 'Liquid Glass', 'Refractive, light-bending glass with bright edge highlights.', 'Premium consumer tech and flagship launches',
    ['#0a84ff', '#bf5af2', '#e9f1fb', '#ffffff', '#0b1b2e', '#d70015'], ['#64b5ff', '#d28bff', '#050b16', '#0f1a2b', '#eaf3ff', '#ff6b7a'], ['Inter', 'Inter'], 'round', 'thin', 'soft'),
  kit('aurora-ui', 'Aurora UI', 'Soft northern-lights gradients behind calm, glassy content.', 'Wellness, travel and creative marketplaces',
    ['#0ea5a4', '#7c3aed', '#effcfb', '#ffffff', '#052e2d', '#be123c'], ['#5eead4', '#c4b5fd', '#04101a', '#0b1f2a', '#e6fffb', '#fb7185'], ['Sora', 'Inter'], 'round', 'thin', 'soft'),
  kit('bento-grid', 'Bento Grid', 'Modular rounded tiles of varied size on a quiet canvas.', 'Feature-rich home pages and curated collections',
    ['#2f6fed', '#ff8a3d', '#f2f2f0', '#ffffff', '#17181c', '#d92d20'], ['#8fb2ff', '#ffa166', '#0d0e11', '#17181d', '#f2f3f5', '#ff7b72'], ['Plus Jakarta Sans', 'Plus Jakarta Sans'], 'round', 'thin', 'soft'),
  kit('editorial-swiss', 'Editorial / Swiss Style', 'Grid, strong type hierarchy, one signal colour, no decoration.', 'Fashion editorial, books, design objects',
    ['#e10600', '#111111', '#f4f4f2', '#ffffff', '#111111', '#e10600'], ['#ff5a52', '#f4f4f2', '#0c0c0c', '#151515', '#f4f4f2', '#ff5a52'], ['Archivo', 'Inter'], 'sharp', 'thin', 'none'),
  kit('brutalism', 'Brutalism', 'Raw and blunt: black outlines, square corners, uppercase, no softness.', 'Streetwear, zines, counter-culture brands',
    ['#000000', '#ffe600', '#e8e8e8', '#ffffff', '#000000', '#ff0000'], ['#ffffff', '#ffe600', '#101010', '#1a1a1a', '#ffffff', '#ff4d4d'], ['Archivo', 'IBM Plex Sans'], 'sharp', 'bold', 'none'),
  kit('neubrutalism', 'Neubrutalism', 'Brutalism made friendly: thick outlines, hard offset shadows, candy colours.', 'Startups, creators, youth and indie products',
    ['#ff5ca8', '#ffd23f', '#fff4e0', '#ffffff', '#111111', '#e03131'], ['#ff8cc6', '#ffd23f', '#15121a', '#211c2b', '#fff4e0', '#ff6b6b'], ['Space Grotesk', 'Work Sans'], 'soft', 'bold', 'retro'),
  kit('retro-vintage', 'Retro / Vintage', 'Warm paper tones, serif character and printed-catalogue charm.', 'Heritage, home goods, books, collectables',
    ['#a4452c', '#2f6f73', '#f1e4c8', '#f8eed6', '#3a2a1c', '#9b1c1c'], ['#e59a7f', '#79bfc3', '#1b130d', '#271c14', '#f3e6c9', '#f27d7d'], ['Fraunces', 'Karla'], 'soft', 'standard', 'retro'),
  kit('y2k', 'Y2K / Retro-Futurism', 'Glossy chrome-and-bubble optimism with a techy grid.', 'Gen-Z fashion, music, gadgets and accessories',
    ['#2f5fef', '#ff4fd8', '#e8f0ff', '#f7faff', '#101a3a', '#d6006a'], ['#8fb0ff', '#ff7ae3', '#070b1f', '#101739', '#e8eeff', '#ff6ba8'], ['Outfit', 'Rubik'], 'round', 'standard', 'lifted'),
  kit('cyberpunk', 'Cyberpunk', 'Neon on near-black with scanlines, caps and glow.', 'Gaming gear, PC hardware, sci-fi merch',
    ['#00f0ff', '#ff2bd6', '#0a0a14', '#12121f', '#e8f6ff', '#ff4d6d'], ['#00f0ff', '#ff2bd6', '#0a0a14', '#12121f', '#e8f6ff', '#ff4d6d'], ['Oswald', 'IBM Plex Sans'], 'sharp', 'standard', 'none'),
  kit('bauhaus', 'Bauhaus', 'Primary red, yellow and blue, geometric order and a visible grid.', 'Design, stationery, furniture, education',
    ['#d62828', '#f2b705', '#f4efe3', '#fffaf0', '#111111', '#d62828'], ['#ff6b5e', '#f6c945', '#101114', '#1a1b20', '#f4efe3', '#ff6b5e'], ['Josefin Sans', 'Karla'], 'sharp', 'bold', 'crisp'),
  kit('maximalism', 'Maximalism', 'More is more: saturated colour, dense pattern, expressive type.', 'Party, decor, fashion and gifting with attitude',
    ['#c026d3', '#f59e0b', '#fff1fb', '#ffffff', '#2a0a33', '#e11d48'], ['#f0abfc', '#fbbf24', '#14031a', '#220a2b', '#fdeaff', '#fb7185'], ['Playfair Display', 'Rubik'], 'soft', 'bold', 'retro'),
  kit('spatial-ui', 'Spatial UI (AR/VR)', 'Floating glass panels with depth, soft glow and generous radii.', 'AR/VR, devices, immersive product demos',
    ['#4f8cff', '#8f6bff', '#dfe7f5', '#f5f8ff', '#101a30', '#d6334a'], ['#8db4ff', '#b39cff', '#05070d', '#0f1522', '#eaf0ff', '#ff7285'], ['Inter', 'Inter'], 'round', 'thin', 'lifted'),
  kit('conversational-ai', 'Conversational / AI-First UI', 'Chat-bubble softness, gentle gradients and a single friendly accent.', 'Assistant-led shopping and support-heavy stores',
    ['#5558e8', '#10b981', '#f7f7fb', '#ffffff', '#1e1b3a', '#dc2626'], ['#a5b4fc', '#34d399', '#0b0b14', '#14141f', '#ececf8', '#f87171'], ['Inter', 'Inter'], 'round', 'thin', 'soft'),
];

export function findKit(id: string): StyleKit | undefined {
  return STYLE_KITS.find((k) => k.id === id);
}

/** What "Default" means: the built-in look. */
export const DEFAULT_STYLE_ID: StyleId = DEFAULT_THEME.style.id;
