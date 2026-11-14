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
    const css = buildThemeCss({ ...DEFAULT_THEME, border: { radius: 'sharp', width: 'standard' } }, 'storefront');
    expect(css).toContain('.rounded-\\[1\\.75rem\\]{border-radius:calc(1.75rem * 0.2)}');
    expect(css).not.toContain('.rounded-full');
    expect(buildThemeCss(DEFAULT_THEME, 'storefront')).not.toContain('.rounded-');
  });
});

describe('border and shadow sections are independent', () => {
  const cfg = (border: { radius: 'sharp' | 'soft' | 'round'; width: 'thin' | 'standard' | 'bold' }, style: 'none' | 'soft' | 'crisp' | 'retro' | 'lifted') => ({
    ...DEFAULT_THEME,
    border,
    shadow: { style },
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
    expect(c.border).toEqual({ radius: 'round', width: 'standard' });
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
