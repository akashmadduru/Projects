# 11. CSS Custom Properties Map

## Design System: Color Palette, Spacing, Typography

### Color Palette (Light & Dark Modes)

#### Light Mode (`catalogLight`)

| Variable | OKLch Value | Purpose | Example Usage |
|----------|------------|---------|---------------|
| `--color-base-100` | oklch(99% 0.002 90) | Main background | Page, card, modal background |
| `--color-base-200` | oklch(97% 0.003 90) | Secondary background | Hovered element, input background |
| `--color-base-300` | oklch(93% 0.004 90) | Tertiary background | Disabled state, placeholder |
| `--color-base-content` | oklch(22% 0.006 90) | Primary text | Body text, headings |
| **--color-primary** | **oklch(58% 0.13 205)** | **Primary action blue** | **Buttons, links, active nav** |
| `--color-primary-content` | oklch(99% 0.01 205) | Text on primary | Button text (white/off-white) |
| `--color-secondary` | oklch(45% 0.01 260) | Secondary accent | Badge, secondary button |
| `--color-secondary-content` | oklch(98% 0.005 260) | Text on secondary | Badge text |
| `--color-accent` | oklch(58% 0.13 205) | Accent (same as primary) | Sidebar active indicator, highlights |
| `--color-accent-content` | oklch(99% 0.01 205) | Text on accent | Accent element text |
| `--color-neutral` | oklch(30% 0.008 90) | Neutral gray | Borders, disabled, secondary text |
| `--color-neutral-content` | oklch(97% 0.003 90) | Text on neutral | Text in neutral elements |
| **--color-info** | **oklch(62% 0.10 235)** | **Info messages** | **Blue info alerts** |
| `--color-info-content` | oklch(98% 0.01 235) | Text on info | Info alert text |
| **--color-success** | **oklch(60% 0.14 150)** | **Success state** | **Green success alerts, checkmarks** |
| `--color-success-content` | oklch(98% 0.01 150) | Text on success | Success alert text |
| **--color-warning** | **oklch(75% 0.15 80)** | **Warning state** | **Yellow/amber warning alerts** |
| `--color-warning-content` | oklch(22% 0.05 80) | Text on warning | Warning alert text (dark for contrast) |
| **--color-error** | **oklch(58% 0.19 25)** | **Error state** | **Red error alerts, destructive buttons** |
| `--color-error-content` | oklch(98% 0.01 25) | Text on error | Error alert text |

#### Dark Mode (`catalogDark`)

| Variable | OKLch Value | Purpose | Shift from Light |
|----------|------------|---------|------------------|
| `--color-base-100` | oklch(17% 0.006 260) | Main background | Much darker (17% vs 99%) |
| `--color-base-200` | oklch(14% 0.006 260) | Secondary background | Darker gray-blue tone |
| `--color-base-300` | oklch(11% 0.005 260) | Tertiary background | Nearly black |
| `--color-base-content` | oklch(92% 0.005 260) | Primary text | Much lighter (92% vs 22%) |
| **--color-primary** | **oklch(72% 0.12 205)** | **Primary action blue** | **Lighter blue for visibility** |
| `--color-primary-content` | oklch(14% 0.02 205) | Text on primary | Dark text on light blue |
| `--color-secondary` | oklch(70% 0.01 260) | Secondary accent | Lighter purple |
| `--color-secondary-content` | oklch(14% 0.01 260) | Text on secondary | Dark text |
| `--color-accent` | oklch(72% 0.12 205) | Accent | Lighter blue |
| `--color-accent-content` | oklch(14% 0.02 205) | Text on accent | Dark text |
| `--color-neutral` | oklch(85% 0.008 260) | Neutral gray | Much lighter |
| `--color-neutral-content` | oklch(16% 0.01 260) | Text on neutral | Dark text |
| **--color-info** | **oklch(72% 0.09 235)** | **Info messages** | **Brighter info blue** |
| `--color-info-content` | oklch(14% 0.03 235) | Text on info | Dark text |
| **--color-success** | **oklch(70% 0.13 150)** | **Success state** | **Brighter success green** |
| `--color-success-content` | oklch(14% 0.03 150) | Text on success | Dark text |
| **--color-warning** | **oklch(80% 0.13 80)** | **Warning state** | **Brighter warning yellow** |
| `--color-warning-content` | oklch(18% 0.04 80) | Text on warning | Darker text for contrast |
| **--color-error** | **oklch(68% 0.17 25)** | **Error state** | **Brighter error red** |
| `--color-error-content` | oklch(14% 0.02 25) | Text on error | Dark text |

### Border & Corner Radius

| Variable | Value | Usage |
|----------|-------|-------|
| `--radius-selector` | 0.5rem (8px) | Button, select, radio, checkbox |
| `--radius-field` | 0.5rem (8px) | Input field, textarea |
| `--radius-box` | 0.625rem (10px) | Card, modal, panel |
| `--border` | 1px | All border widths |

### Depth & Effects

| Variable | Value | Purpose |
|----------|-------|---------|
| `--depth` | 0 | Shadow elevation (daisyUI feature, not used in this app) |
| `--noise` | 0 | Background grain (daisyUI feature, disabled) |

### Typography

#### Fonts
```css
--font-sans: 'Inter', ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
--font-mono: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
```

#### Type Scale (Via Tailwind)

| Utility Class | Font Size | Line Height | Typical Use |
|---------------|-----------|------------|------------|
| `text-xs` | 0.75rem (12px) | 1rem | Helper text, captions |
| `text-sm` | 0.875rem (14px) | 1.25rem | Secondary copy, labels |
| `text-base` | 1rem (16px) | 1.5rem | Body text |
| `text-lg` | 1.125rem (18px) | 1.75rem | Subheadings |
| `text-xl` | 1.25rem (20px) | 1.75rem | Headings |
| `text-2xl` | 1.5rem (24px) | 2rem | Page titles |
| `text-3xl` | 1.875rem (30px) | 2.25rem | Hero titles |
| `text-4xl` | 2.25rem (36px) | 2.5rem | Very large titles |

#### Weight Scale
- `font-normal` — 400
- `font-medium` — 500 (labels, nav items)
- `font-semibold` — 600 (subheadings)
- `font-bold` — 700 (headings, emphasis)

### Spacing Scale (Tailwind)

| Utility | Rem | Px | Use Case |
|---------|-----|----|---------| 
| `p-2` | 0.5rem | 8px | Small padding (labels, badges) |
| `p-3` | 0.75rem | 12px | Form field padding |
| `p-4` | 1rem | 16px | Card padding, section padding |
| `p-6` | 1.5rem | 24px | Modal padding, large spacing |
| `p-8` | 2rem | 32px | Page padding, major sections |
| `gap-2` | 0.5rem | 8px | Small gap between items |
| `gap-3` | 0.75rem | 12px | Form field spacing |
| `gap-4` | 1rem | 16px | Component spacing |
| `gap-6` | 1.5rem | 24px | Section spacing |

### Component Color Mapping

#### Buttons
```css
.btn-primary {
  background: var(--color-primary);
  color: var(--color-primary-content);
}

.btn:hover {
  background: color-mix(in oklch, var(--color-primary) 85%, transparent);
}

.btn:disabled {
  background: var(--color-base-300);
  color: var(--color-base-content);
  opacity: 0.5;
}
```

#### Form Inputs
```css
.input {
  background: var(--color-base-100);
  border: 1px solid color-mix(in oklch, var(--color-base-content) 20%, transparent);
  color: var(--color-base-content);
}

.input:focus {
  border-color: var(--color-primary);
  outline: 2px solid color-mix(in oklch, var(--color-primary) 30%, transparent);
}
```

#### Cards
```css
.card {
  background: var(--color-base-100);
  border: 1px solid color-mix(in oklch, var(--color-base-content) 8%, transparent);
  border-radius: var(--radius-box);
}

.card:hover {
  box-shadow: 0 8px 24px -8px color-mix(in oklch, var(--color-neutral) 25%, transparent);
}
```

#### Navigation
```css
.nav-item {
  color: color-mix(in oklch, var(--color-base-content) 65%, transparent);
  border-radius: var(--radius-field);
}

.nav-item:hover {
  background: color-mix(in oklch, var(--color-base-content) 5%, transparent);
}

.nav-item-active {
  color: var(--color-base-content);
  background: color-mix(in oklch, var(--color-accent) 8%, transparent);
  border-left: 2px solid var(--color-accent);
}
```

#### Alerts
```css
.alert-success {
  background: color-mix(in oklch, var(--color-success) 15%, transparent);
  border: 1px solid color-mix(in oklch, var(--color-success) 50%, transparent);
  color: color-mix(in oklch, var(--color-success) 80%, var(--color-base-content));
}

.alert-error {
  background: color-mix(in oklch, var(--color-error) 15%, transparent);
  border: 1px solid color-mix(in oklch, var(--color-error) 50%, transparent);
  color: color-mix(in oklch, var(--color-error) 80%, var(--color-base-content));
}

.alert-warning {
  background: color-mix(in oklch, var(--color-warning) 15%, transparent);
  border: 1px solid color-mix(in oklch, var(--color-warning) 50%, transparent);
  color: var(--color-warning-content);
}
```

### Custom Component Classes

Defined in `src/style.css` `@layer components`:

#### `.app-sidebar`
- `background: var(--color-base-100)`
- `border-right: 1px solid color-mix(..., var(--color-base-content) 8%, ...)`
- Used by: AppShell sidebar

#### `.nav-item` / `.nav-item-active`
- Muted text color, subtle hover, left accent bar on active
- Used by: Sidebar navigation

#### `.app-header`
- `background: var(--color-base-100)`
- `border-bottom: 1px solid ...`
- Fixed height: 56px
- Used by: Top navigation bar

#### `.stat-card`
- Card for dashboard metrics
- `background: var(--color-base-100)`
- `border: 1px solid ...`
- `padding: 1.25rem`
- Used by: DashboardView

#### `.data-card`
- Generic panel/container
- `background: var(--color-base-100)`
- `border: 1px solid ...`
- `border-radius: var(--radius-box)`
- `padding: 1.5rem`
- Used by: Most panels, lists, forms

#### `.data-card-elevated`
- Same as `.data-card` plus shadow
- `box-shadow: 0 8px 24px -8px color-mix(...)`
- Used by: Modals, dropdowns, floating overlays

#### `.status-dot` / `.status-dot-success` / `.status-dot-neutral`
- 6x6px inline indicator dot
- Used by: Status badges, product active/inactive indicators

#### `.table-minimal`
- Uppercase gray headers
- Hairline row dividers
- Subtle hover background
- Used by: All data tables (products, inventory)

### Dark Mode Toggle Mechanism

```typescript
// In useTheme() composable:
document.documentElement.dataset.theme = 'catalogDark';  // or 'catalogLight'
```

CSS media query in Tailwind:
```css
@media (prefers-color-scheme: dark) {
  /* System prefers dark; theme selector overrides this */
}

html[data-theme='catalogDark'] {
  --color-base-100: oklch(17% ...);
  --color-base-content: oklch(92% ...);
  /* All other dark palette vars */
}
```

### Accessibility Considerations

1. **Color Contrast:**
   - Text on `--color-base-100`: `--color-base-content` (WCAG AAA)
   - Button text on primary: `--color-primary-content` (WCAG AAA)
   - Alert backgrounds + borders maintain WCAG AA minimum

2. **Reduced Motion:**
   - No automatic transitions (user must explicitly toggle theme)
   - Prefers `prefers-reduced-motion` for animations

3. **Color Independence:**
   - Error/success states use both color AND icons/text
   - Not color-only indicators

### File References

- **Color definitions:** `src/style.css` (lines 22-94)
- **Component classes:** `src/style.css` (lines 106-225)
- **Theme toggle:** `src/composables/useTheme.ts`
- **Bootstrap theme:** `index.html` (inline script)

### Visual Reference: Light Mode Palette

```
Background (--color-base-100):     Nearly white (99% lightness)
Foreground (--color-base-content): Nearly black (22% lightness)
Primary:                           Blue (58% lightness, hue 205)
Success:                           Green (60% lightness, hue 150)
Error:                             Red (58% lightness, hue 25)
Warning:                           Yellow (75% lightness, hue 80)
Neutral:                           Gray (30% lightness, hue 90)
```

### Visual Reference: Dark Mode Palette

```
Background (--color-base-100):     Very dark blue (17% lightness)
Foreground (--color-base-content): Nearly white (92% lightness)
Primary:                           Brighter blue (72% lightness, hue 205)
Success:                           Brighter green (70% lightness, hue 150)
Error:                             Brighter red (68% lightness, hue 25)
Warning:                           Brighter yellow (80% lightness, hue 80)
Neutral:                           Light gray (85% lightness, hue 260)
```

All colors use **OKLch color space** for perceptually uniform lightness across hue families.

