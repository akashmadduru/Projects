import { describe, expect, it } from 'vitest';

import {
  DEFAULT_THEME,
  buildThemeCss,
  contrast,
  contrastChecks,
  customCssProblem,
  normalizeTheme,
  toTriple,
} from '@/lib/themeEngine';
import { THEME_PRESETS } from '@/lib/themePresets';

describe('themeEngine', () => {
  it('computes WCAG contrast', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 0);
    expect(contrast('#777777', '#777777')).toBeCloseTo(1, 5);
  });

  it('emits storefront HSL triples and admin daisyUI vars', () => {
    expect(toTriple('#ffffff')).toBe('0 0% 100%');
    const store = buildThemeCss(DEFAULT_THEME, 'storefront');
    expect(store).toContain('--color-primary:');
    expect(store).toContain('[data-theme="dark"]');
    const admin = buildThemeCss(DEFAULT_THEME, 'admin');
    expect(admin).toContain("[data-theme='catalogLight']");
    expect(admin).toContain('--color-base-100:');
  });

  it('drops dangerous custom CSS instead of applying it', () => {
    expect(customCssProblem('a{background:url(http://x)}')).toBeTruthy();
    expect(customCssProblem('@import "x";')).toBeTruthy();
    expect(customCssProblem('</style><script>')).toBeTruthy();
    expect(customCssProblem('.x{color:red}')).toBeNull();
    const cfg = normalizeTheme({ customCss: 'a{background:url(//evil)}' });
    expect(cfg.customCss).toBe('');
  });

  it('normalises untrusted input to a complete config', () => {
    const cfg = normalizeTheme({ light: { primary: 'not-a-colour' }, fonts: { display: 'Comic Sans' }, radius: 'x' });
    expect(cfg.light.primary).toBe(DEFAULT_THEME.light.primary);
    expect(cfg.fonts.display).toBe(DEFAULT_THEME.fonts.display);
    expect(cfg.border.radius).toBe('soft');
  });

  describe.each(THEME_PRESETS.map((p) => [p.label, p] as const))('preset %s', (_label, p) => {
    it.each(['light', 'dark'] as const)('has readable body text in %s mode', (mode) => {
      const [onBg, onSurface] = contrastChecks(p.config[mode]);
      expect(onBg!.ratio).toBeGreaterThanOrEqual(4.5);
      expect(onSurface!.ratio).toBeGreaterThanOrEqual(4.5);
    });
    it('has readable buttons in both modes', () => {
      for (const mode of ['light', 'dark'] as const) {
        const checks = contrastChecks(p.config[mode]);
        expect(checks[2]!.ratio).toBeGreaterThanOrEqual(4.5); // on-primary
        expect(checks[3]!.ratio).toBeGreaterThanOrEqual(4.5); // on-accent
      }
    });
  });
});

describe('storefront radius', () => {
  it('escapes arbitrary-value radius selectors and leaves pills alone', () => {
    const css = buildThemeCss({ ...DEFAULT_THEME, border: { ...DEFAULT_THEME.border, radius: 'sharp' } }, 'storefront');
    expect(css).toContain('.rounded-\\[1\\.75rem\\]{border-radius:calc(1.75rem * 0.2)}');
    expect(css).not.toContain('.rounded-full');
    expect(buildThemeCss(DEFAULT_THEME, 'storefront')).not.toContain('.rounded-');
  });
});

describe('border and shadow sections are independent', () => {
  const cfg = (border: { radius: 'sharp' | 'soft' | 'round'; width: 'thin' | 'standard' | 'bold' }, style: 'none' | 'soft' | 'crisp' | 'retro' | 'lifted') => ({
    ...DEFAULT_THEME,
    border: { ...DEFAULT_THEME.border, ...border },
    shadow: { ...DEFAULT_THEME.shadow, style },
  });

  it('built-in border + retro shadow emit nothing extra (stylesheet untouched)', () => {
    for (const target of ['admin', 'storefront'] as const) {
      const css = buildThemeCss(DEFAULT_THEME, target);
      expect(css).not.toContain('--shadow-card');
      expect(css).not.toContain('--cw-shadow');
      expect(css).not.toContain('--cw-bw');
      expect(css).not.toContain('.border-2');
    }
  });

  it('changing only the shadow leaves borders alone, and vice versa', () => {
    const shadowOnly = buildThemeCss(cfg({ radius: 'soft', width: 'standard' }, 'lifted'), 'storefront');
    expect(shadowOnly).toContain('--shadow-card:0 10px 30px');
    expect(shadowOnly).not.toContain('.border-2');
    const borderOnly = buildThemeCss(cfg({ radius: 'soft', width: 'bold' }, 'retro'), 'storefront');
    expect(borderOnly).toContain('.border-2{border-width:3px}');
    expect(borderOnly).not.toContain('--shadow-card');
  });

  it('maps to the admin variables', () => {
    const css = buildThemeCss(cfg({ radius: 'soft', width: 'thin' }, 'none'), 'admin');
    expect(css).toContain('--cw-bw:1px');
    expect(css).toContain('--cw-shadow-card:none');
  });

  it('upgrades legacy configs that only had a radius', () => {
    const c = normalizeTheme({ radius: 'round' });
    expect(c.border).toEqual({ radius: 'round', width: 'standard', color: 'default', style: 'solid' });
    expect(c.shadow.style).toBe('retro');
  });
});

describe('design styles', () => {
  it('has a kit for every non-default style, and nothing extra', async () => {
    const { STYLE_KITS } = await import('@/lib/themeStyles');
    const { STYLE_IDS } = await import('@/lib/themeEngine');
    expect(STYLE_KITS.map((k) => k.id).sort()).toEqual(STYLE_IDS.filter((i) => i !== 'default').sort());
  });

  it('default style injects nothing; unknown ids normalise to default', () => {
    expect(buildThemeCss(DEFAULT_THEME, 'storefront')).not.toContain('--fx-');
    expect(normalizeTheme({ style: { id: 'nope' } }).style.id).toBe('default');
    expect(normalizeTheme({ style: { id: 'glassmorphism' } }).style.id).toBe('glassmorphism');
  });

  it('glass styles emit blur on both targets with target-specific selectors', () => {
    const cfg = { ...DEFAULT_THEME, style: { id: 'glassmorphism' as const } };
    const store = buildThemeCss(cfg, 'storefront');
    const admin = buildThemeCss(cfg, 'admin');
    expect(store).toContain('.bg-surface');
    expect(store).toContain('backdrop-filter:blur(16px)');
    expect(admin).toContain('.data-card');
    expect(admin).toContain("[data-theme='catalogDark']{--fx-surface");
    expect(store).toContain('radial-gradient'); // aurora backdrop
  });

  it('each style kit has readable text and buttons in both modes', async () => {
    const { STYLE_KITS } = await import('@/lib/themeStyles');
    for (const k of STYLE_KITS) {
      for (const mode of ['light', 'dark'] as const) {
        const c = contrastChecks(k[mode]);
        const bad = c.slice(0, 4).filter((x) => !x.ok).map((x) => `${k.id}/${mode}: ${x.label} ${x.ratio.toFixed(1)}`);
        expect(bad).toEqual([]);
      }
    }
  });
});

describe('palette tools and comfort options', () => {
  it('generated palettes are accessible in both modes, whatever the brand colour', async () => {
    const { generatePalette, randomPalette } = await import('@/lib/themeEngine');
    for (const base of ['#ff0000', '#00ff00', '#0000ff', '#ffff00', '#888888', '#176d65', '#ff00ff']) {
      for (const mode of ['light', 'dark'] as const) {
        const checks = contrastChecks(generatePalette(base, mode));
        expect(checks.filter((c) => !c.ok && c.label !== 'Button text on accent').map((c) => `${base}/${mode}:${c.label}`)).toEqual([]);
      }
    }
    let n = 0;
    const rand = () => ((n = (n * 7 + 3) % 11) + 1) / 12;
    expect(contrastChecks(randomPalette('dark', rand))[0]!.ok).toBe(true);
  });

  it('derives an accessible dark palette from a light one and auto-fixes bad contrast', async () => {
    const { deriveDarkFromLight, autoFixPalette } = await import('@/lib/themeEngine');
    const dark = deriveDarkFromLight(DEFAULT_THEME.light);
    expect(contrastChecks(dark)[0]!.ok).toBe(true);
    const bad = { ...DEFAULT_THEME.light, text: '#cccccc', primary: '#eeeeee' };
    const fixed = autoFixPalette(bad, 'light');
    expect(contrast(fixed.text, fixed.background)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(fixed.primary, fixed.surface)).toBeGreaterThanOrEqual(3);
  });

  it('emits text-size and motion CSS only when changed', () => {
    expect(buildThemeCss(DEFAULT_THEME, 'storefront')).not.toContain('font-size:');
    const big = buildThemeCss({ ...DEFAULT_THEME, options: { textScale: 'large', motion: 'none' } }, 'storefront');
    expect(big).toContain('html{font-size:109%}');
    expect(big).toContain('animation:none!important');
    expect(normalizeTheme({ options: { textScale: 'huge', motion: 'x' } }).options).toEqual({ textScale: 'default', motion: 'full' });
  });
});

describe('preview links', () => {
  it('round-trips a config through the URL hash, including non-ASCII brand text', async () => {
    const { encodeThemeForUrl, readPreviewFromHash } = await import('@/lib/themeEngine');
    const cfg = { ...DEFAULT_THEME, brand: { ...DEFAULT_THEME.brand, name: 'Café Ünïcode ✦', tagline: 'हिन्दी' } };
    const hash = `#other=1&cwpreview=${encodeThemeForUrl(cfg)}`;
    expect(readPreviewFromHash(hash)).toEqual(cfg);
    expect(readPreviewFromHash('#cwpreview=not-base64!!')).toBeNull();
    expect(readPreviewFromHash('#nothing')).toBeNull();
  });
});

describe('extended controls (borders, shadows, cards, buttons, type)', () => {
  it('built-in defaults for every new control still emit nothing extra', () => {
    for (const target of ['admin', 'storefront'] as const) {
      const css = buildThemeCss(DEFAULT_THEME, target);
      // Only the palette blocks (light + dark) define the outline; no extra border-colour override.
      expect(css.match(/--color-outline:/g)?.length ?? 0).toBe(target === 'storefront' ? 2 : 0);
      expect(css).not.toContain('--cw-outline:hsl');
      expect(css).not.toContain('text-transform');
      expect(css).not.toContain('line-height');
      expect(css).not.toContain('translate');
    }
  });

  it('normalises unknown values for the new fields back to built-in', () => {
    const c = normalizeTheme({
      border: { color: 'neon' },
      shadow: { style: 'glow', intensity: 'max', tint: 'rainbow' },
      card: { hover: 'spin', fill: 'tinted' },
      button: { shape: 'blob', fill: 'outline', text: 'upper' },
      fonts: { display: 'Geist', body: 'Figtree', headingWeight: '900', tracking: 'wide', lineHeight: 'x' },
    });
    expect(c.border.color).toBe('default');
    expect(c.shadow).toEqual({ style: 'glow', intensity: 'medium', tint: 'ink' });
    expect(c.card).toEqual({ hover: 'default', fill: 'tinted', shape: 'default', accent: 'none' });
    expect(c.button).toEqual({ shape: 'default', fill: 'outline', text: 'upper', hover: 'default' });
    expect(c.fonts).toEqual({ display: 'Geist', body: 'Figtree', headingWeight: 'default', tracking: 'wide', lineHeight: 'default' });
  });

  it('shadow intensity scales and tint recolours; retro is only emitted when tuned', () => {
    const shadow = (s: Partial<typeof DEFAULT_THEME.shadow>) => buildThemeCss({ ...DEFAULT_THEME, shadow: { ...DEFAULT_THEME.shadow, ...s } }, 'storefront');
    expect(shadow({ style: 'retro' })).not.toContain('--shadow-card');
    expect(shadow({ style: 'retro', intensity: 'strong' })).toContain('--shadow-card:6px 6px 0 hsl(var(--color-ink))');
    expect(shadow({ style: 'retro', tint: 'brand' })).toContain('--shadow-card:4px 4px 0 hsl(var(--color-primary))');
    expect(shadow({ style: 'glow' })).toContain('hsl(var(--color-primary) /'); // ink glow falls back to brand
    expect(shadow({ style: 'layered' }).match(/--shadow-card:([^;]*)/)![1]!.split(',').length).toBe(4);
    const admin = buildThemeCss({ ...DEFAULT_THEME, shadow: { style: 'long', intensity: 'medium', tint: 'accent' } }, 'admin');
    expect(admin).toContain('--cw-shadow-card:7px 7px 0 var(--cw-accent)');
  });

  it('border colour and borderless map to both apps without hiding admin inputs', () => {
    const cfg = { ...DEFAULT_THEME, border: { radius: 'square' as const, width: 'none' as const, color: 'brand' as const, style: 'solid' as const } };
    const store = buildThemeCss(cfg, 'storefront');
    expect(store).toContain('.border-2{border-width:0px}');
    expect(store).toContain('--color-outline:');
    expect(store).toContain('--radius-lg:0.000rem');
    const admin = buildThemeCss(cfg, 'admin');
    expect(admin).toContain('--cw-bw:0px;--border:1px');
    expect(admin).toContain("[data-theme='catalogDark']{--cw-outline:");
  });

  it('card and button choices target each app’s own selectors', () => {
    const cfg = {
      ...DEFAULT_THEME,
      card: { ...DEFAULT_THEME.card, hover: 'lift' as const, fill: 'tinted' as const },
      button: { ...DEFAULT_THEME.button, shape: 'pill' as const, fill: 'outline' as const, text: 'upper' as const },
    };
    const store = buildThemeCss(cfg, 'storefront');
    expect(store).toContain('.shadow-card{background-color:color-mix(');
    expect(store).toContain('.hover\\:shadow-card-hover:hover{translate:0 -6px');
    expect(store).toContain('.bg-cta-gradient{background-image:none;background-color:transparent');
    expect(store).toContain('text-transform:uppercase');
    const admin = buildThemeCss(cfg, 'admin');
    expect(admin).toContain('a.stat-card:hover{transform:translateY(-6px)');
    expect(admin).toContain('.btn.btn-primary{background-image:none');
    expect(admin).toContain('border-radius:9999px');
  });

  it('heading weight, tracking and line height are emitted only when changed', () => {
    const css = buildThemeCss({ ...DEFAULT_THEME, fonts: { ...DEFAULT_THEME.fonts, headingWeight: 'medium', tracking: 'tight', lineHeight: 'relaxed' } }, 'admin');
    expect(css).toContain('h1,h2,h3,.font-display{font-weight:500}');
    expect(css).toContain('letter-spacing:-0.025em');
    expect(css).toContain('body{line-height:1.75}');
  });

  it('every serif in the font list gets a serif fallback stack', () => {
    const css = buildThemeCss({ ...DEFAULT_THEME, fonts: { ...DEFAULT_THEME.fonts, display: 'Bodoni Moda' } }, 'storefront');
    expect(css).toContain("'Bodoni Moda', Georgia");
  });
});

describe('colour suggestions', () => {
  it('every harmony of several brand colours is readable in both modes', async () => {
    const { generatePalette, HARMONIES } = await import('@/lib/themeEngine');
    for (const base of ['#176d65', '#e11d48', '#facc15', '#1e3a8a', '#777777']) {
      for (const h of HARMONIES) {
        for (const mode of ['light', 'dark'] as const) {
          const bad = contrastChecks(generatePalette(base, mode, { harmony: h.value }))
            .slice(0, 4)
            .filter((c) => !c.ok)
            .map((c) => `${base}/${h.value}/${mode}: ${c.label} ${c.ratio.toFixed(2)}`);
          expect(bad).toEqual([]);
        }
      }
    }
  });

  it('every curated colour combination is readable in both modes', async () => {
    const { COLOUR_COMBOS } = await import('@/lib/themeColours');
    expect(COLOUR_COMBOS.length).toBeGreaterThanOrEqual(20);
    for (const c of COLOUR_COMBOS) {
      for (const mode of ['light', 'dark'] as const) {
        const bad = contrastChecks(c[mode]).slice(0, 4).filter((x) => !x.ok).map((x) => `${c.id}/${mode}: ${x.label} ${x.ratio.toFixed(2)}`);
        expect(bad).toEqual([]);
      }
    }
  });

  it('preset and font-pair ids are unique, and every pairing uses allowed fonts', async () => {
    const { FONT_PAIRS, FONT_OPTIONS } = await import('@/lib/themeEngine');
    const ids = THEME_PRESETS.map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(new Set(FONT_PAIRS.map((p) => p.id)).size).toBe(FONT_PAIRS.length);
    for (const p of FONT_PAIRS) {
      expect(FONT_OPTIONS).toContain(p.display);
      expect(FONT_OPTIONS).toContain(p.body);
    }
    for (const p of THEME_PRESETS) expect(normalizeTheme(p.config)).toEqual(p.config);
  });
});

describe('10x option catalogue', () => {
  it('ships the expanded catalogue', async () => {
    const E = await import('@/lib/themeEngine');
    const { COLOUR_COMBOS, COLOUR_CATEGORIES } = await import('@/lib/themeColours');
    expect(COLOUR_COMBOS.length).toBe(240);
    expect(COLOUR_CATEGORIES.length).toBe(13);
    expect(new Set(COLOUR_COMBOS.map((c) => c.id)).size).toBe(COLOUR_COMBOS.length);
    expect(E.FONT_OPTIONS.length).toBeGreaterThanOrEqual(190);
    expect(new Set(E.FONT_OPTIONS).size).toBe(E.FONT_OPTIONS.length);
    expect(E.FONT_PAIRS.length).toBeGreaterThanOrEqual(150);
    expect(E.SHADOW_STYLES.length).toBe(18);
    expect(E.CARD_HOVERS.length + E.CARD_FILLS.length + E.CARD_SHAPES.length + E.CARD_ACCENTS.length).toBe(29);
    expect(E.BUTTON_SHAPES.length + E.BUTTON_FILLS.length + E.BUTTON_TEXTS.length + E.BUTTON_HOVERS.length).toBe(26);
  });

  it('every option of every group emits valid-looking CSS for both apps without leaking undefined', async () => {
    const E = await import('@/lib/themeEngine');
    const groups: [keyof typeof DEFAULT_THEME, string, readonly { value: string }[]][] = [
      ['border', 'radius', E.RADIUS_KINDS], ['border', 'width', E.BORDER_WIDTHS], ['border', 'color', E.BORDER_COLORS], ['border', 'style', E.BORDER_STYLES],
      ['shadow', 'style', E.SHADOW_STYLES], ['shadow', 'intensity', E.SHADOW_INTENSITIES], ['shadow', 'tint', E.SHADOW_TINTS],
      ['card', 'hover', E.CARD_HOVERS], ['card', 'fill', E.CARD_FILLS], ['card', 'shape', E.CARD_SHAPES], ['card', 'accent', E.CARD_ACCENTS],
      ['button', 'shape', E.BUTTON_SHAPES], ['button', 'fill', E.BUTTON_FILLS], ['button', 'text', E.BUTTON_TEXTS], ['button', 'hover', E.BUTTON_HOVERS],
    ];
    for (const [part, key, list] of groups) {
      for (const { value } of list) {
        const cfg = normalizeTheme({ ...DEFAULT_THEME, shadow: { ...DEFAULT_THEME.shadow, style: 'soft' }, [part]: { ...(DEFAULT_THEME[part] as object), [key]: value } });
        expect((cfg[part] as Record<string, string>)[key], `${part}.${key}=${value} survives normalise`).toBe(value);
        for (const target of ['admin', 'storefront'] as const) {
          const css = buildThemeCss(cfg, target);
          expect(css, `${part}.${key}=${value} ${target}`).not.toMatch(/undefined|NaN|\[object/);
          // braces balance: every rule closes
          expect(css.split('{').length, `${part}.${key}=${value} ${target} braces`).toBe(css.split('}').length);
        }
        const prev = JSON.stringify([E.previewShadows(cfg, 'dark'), E.previewCardBackground(cfg, 'light'), E.previewButtonStyle(cfg, 'dark'), E.previewOutline(cfg, 'light')]);
        expect(prev).not.toMatch(/undefined|NaN/);
      }
    }
  });

  it('duotone shadows use brand at rest and accent on hover; deal tint uses the sale colour', () => {
    const css = buildThemeCss({ ...DEFAULT_THEME, shadow: { style: 'crisp', intensity: 'medium', tint: 'duo' } }, 'storefront');
    expect(css).toContain('--shadow-card:2px 2px 0 hsl(var(--color-primary))');
    expect(css).toContain('--shadow-card-hover:4px 4px 0 hsl(var(--color-accent))');
    const deal = buildThemeCss({ ...DEFAULT_THEME, shadow: { style: 'drop', intensity: 'medium', tint: 'danger' } }, 'admin');
    expect(deal).toContain('--cw-shadow-card:0 3px 0 var(--cw-deal)');
  });

  it('card patterns and accent bars combine into one background with matching layer lists', () => {
    const cfg = { ...DEFAULT_THEME, card: { ...DEFAULT_THEME.card, fill: 'grid' as const, accent: 'top' as const } };
    const css = buildThemeCss(cfg, 'storefront');
    const rule = css.match(/\.shadow-card\{background-color[^}]*\}/)![0];
    const images = rule.match(/background-image:([^;]*)/)![1]!.split(/,(?=(?:linear|radial|repeating)-gradient)/).length;
    expect(images).toBe(3); // bar + two grid lines
    expect(rule.match(/background-size:([^;]*)/)![1]!.split(',').length).toBe(3);
    expect(rule.match(/background-repeat:([^;}]*)/)![1]!.split(',').length).toBe(3);
  });

  it('serif, slab, mono and script families get matching fallback stacks', () => {
    const stack = (display: string) => buildThemeCss({ ...DEFAULT_THEME, fonts: { ...DEFAULT_THEME.fonts, display } }, 'admin');
    expect(stack('Spectral')).toContain("'Spectral', Georgia");
    expect(stack('Roboto Slab')).toContain("'Roboto Slab', Rockwell");
    expect(stack('JetBrains Mono')).toContain("'JetBrains Mono', ui-monospace");
    expect(stack('Caveat')).toContain("'Caveat', 'Segoe Print'");
  });
});
