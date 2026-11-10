import type { Config } from 'tailwindcss'

export default {
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
  ],
  darkMode: ['selector', '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        primary: 'hsl(var(--color-primary) / <alpha-value>)',
        secondary: 'hsl(var(--color-secondary) / <alpha-value>)',
        accent: 'hsl(var(--color-accent) / <alpha-value>)',
        surface: 'hsl(var(--color-surface) / <alpha-value>)',
        'surface-variant': 'hsl(var(--color-surface-variant) / <alpha-value>)',
        outline: 'hsl(var(--color-outline) / <alpha-value>)',
        error: 'hsl(var(--color-error) / <alpha-value>)',
        'on-primary': 'hsl(var(--color-on-primary) / <alpha-value>)',
        'on-secondary': 'hsl(var(--color-on-secondary) / <alpha-value>)',
        'on-surface': 'hsl(var(--color-on-surface) / <alpha-value>)',
        'on-surface-muted': 'hsl(var(--color-on-surface-muted) / <alpha-value>)',
        'on-error': 'hsl(var(--color-on-error) / <alpha-value>)',
      },
      backgroundColor: {
        base: 'hsl(var(--color-surface) / <alpha-value>)',
      },
      textColor: {
        base: 'hsl(var(--color-on-surface) / <alpha-value>)',
        muted: 'hsl(var(--color-outline) / <alpha-value>)',
      },
      borderColor: {
        DEFAULT: 'hsl(var(--color-outline) / <alpha-value>)',
      },
    },
  },
  plugins: [],
} satisfies Config
