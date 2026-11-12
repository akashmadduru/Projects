import type { Config } from 'tailwindcss'

// Loaded by src/style.css via `@config`. Every color is a token from style.css, so light/dark
// theming happens entirely in CSS variables.
const token = (name: string) => `hsl(var(--color-${name}) / <alpha-value>)`

export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: ['selector', '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        background: token('background'),
        surface: token('surface'),
        'surface-variant': token('surface-variant'),
        outline: token('outline'),
        'on-surface': token('on-surface'),
        'on-surface-muted': token('on-surface-muted'),
        brand: token('brand'),
        'brand-2': token('brand-2'),
        'on-brand': token('on-brand'),
        primary: token('primary'),
        'on-primary': token('on-primary'),
        'primary-container': token('primary-container'),
        'on-primary-container': token('on-primary-container'),
        accent: token('accent'),
        'accent-2': token('accent-2'),
        'accent-strong': token('accent-strong'),
        'on-accent': token('on-accent'),
        ink: token('ink'),
        deal: token('deal'),
        'deal-2': token('deal-2'),
        'vivid-teal': token('vivid-teal'),
        'vivid-sky': token('vivid-sky'),
        'vivid-violet': token('vivid-violet'),
        'vivid-pink': token('vivid-pink'),
        'vivid-coral': token('vivid-coral'),
        'vivid-amber': token('vivid-amber'),
        'vivid-lime': token('vivid-lime'),
        success: token('success'),
        warning: token('warning'),
        error: token('error'),
        'on-error': token('on-error'),
      },
      textColor: {
        base: token('on-surface'),
        muted: token('on-surface-muted'),
      },
      borderColor: {
        DEFAULT: token('outline'),
      },
      fontFamily: {
        display: ['Righteous', '"Poppins"', 'system-ui', 'sans-serif'],
        body: ['"Poppins"', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
} satisfies Config
