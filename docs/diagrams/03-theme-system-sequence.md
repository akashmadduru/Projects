# 3. Theme System Sequence

## Sequence Diagram: Light ↔ Dark Theme Toggle

```mermaid
sequenceDiagram
    actor User
    participant Toggle as ThemeToggle.vue
    participant Composable as useTheme()
    participant DOM as document<br/>.documentElement
    participant Storage as localStorage
    participant Renderer as Vue/CSS<br/>Renderer
    
    rect rgb(200, 240, 220)
        Note over User,Renderer: Initial Page Load (Bootstrap)
    end
    
    alt prefers-color-scheme: dark
        Toggle->>DOM: index.html inline script<br/>reads media query
        DOM-->>Toggle: true → default is dark
        Toggle->>DOM: setAttribute('data-theme',<br/>'catalogDark')
    else prefers-color-scheme: light or no preference
        Toggle->>DOM: index.html inline script<br/>reads media query
        DOM-->>Toggle: false → default is light
        Toggle->>DOM: setAttribute('data-theme',<br/>'catalogLight')
    end
    
    Toggle->>Storage: Write to localStorage<br/>['theme'] = selected theme
    
    rect rgb(255, 240, 220)
        Note over User,Renderer: Runtime: User Toggles Theme
    end
    
    User->>Toggle: 1. Click theme toggle button
    Toggle->>Composable: 2. call useTheme().toggleTheme()
    
    Composable->>Composable: 3. theme.value =<br/>theme.value === 'catalogDark'<br/>  ? 'catalogLight'<br/>  : 'catalogDark'
    
    Composable->>DOM: 4. document.documentElement<br/>.dataset.theme = 'catalogLight'
    
    Composable->>Storage: 5. localStorage.setItem<br/>('theme', 'catalogLight')
    
    par CSS Variable Injection
        DOM->>DOM: 6a. CSS media matcher<br/>detects [data-theme] change
        
        DOM->>DOM: 6b. @plugin "daisyui/theme"<br/>css rules apply new palette:
        Note over DOM: --color-bg: oklch(99% ...)<br/>--color-text: oklch(22% ...)<br/>--color-primary: oklch(58% ...)<br/>--color-success: oklch(60% ...)<br/>--color-error: oklch(58% ...)<br/>--color-warning: oklch(75% ...)
        
    and Vue Reactivity
        Composable->>Renderer: 6c. theme ref changed<br/>→ Vue detects mutation
        Renderer->>Renderer: 6d. isDark computed<br/>re-evaluates
        Renderer->>Renderer: 6e. All consuming components<br/>re-render if they use isDark
        Renderer-->>User: 6f. View updates<br/>(icons, badges, conditional classnames)
    end
    
    par Immediate Visual Update
        DOM->>DOM: 7. Tailwind utilities<br/>re-evaluate against new CSS vars
        DOM->>Renderer: All elements using<br/>text, bg, border colors<br/>instantly switch palette
    and Deferred Component Update
        Renderer-->>User: 8. Components using<br/>isDark computed finish<br/>their re-render cycle
    end
    
    User-->>User: 9. Page is now in Dark mode
    Note over User,Renderer: All text, backgrounds, borders,<br/>shadows, borders use dark palette<br/>from catalogDark theme
    
    rect rgb(220, 220, 255)
        Note over User,Renderer: Page Reload with Persisted Theme
    end
    
    User->>User: 10. User reloads page (F5)
    Toggle->>Storage: 11. index.html script reads<br/>localStorage['theme']
    Storage-->>Toggle: 12. Return 'catalogLight'
    Toggle->>DOM: 13. setAttribute('data-theme',<br/>'catalogLight')
    DOM-->>User: 14. Page re-renders immediately<br/>in light theme (before Vue hydrates)
    
    Note over Toggle,Renderer: This is what avoids FOUC<br/>(Flash of Unstyled Content)
```

## Narrative Description

### Phase 1: Bootstrap (Before Vue Hydrates)

An **inline script in `index.html`** runs synchronously before Vue mounts:

```html
<script>
  const stored = localStorage.getItem('theme');
  const preferred = window.matchMedia('(prefers-color-scheme: dark)').matches
    ? 'catalogDark'
    : 'catalogLight';
  const theme = stored ?? preferred;
  document.documentElement.dataset.theme = theme;
</script>
```

This ensures:
- No **FOUC** (Flash of Unstyled Content) — the correct theme's CSS is loaded before paint
- `document.documentElement.dataset.theme` is set before Vue mounts
- Theme persists across reloads (from localStorage)
- System preference is used on first visit (fallback to `prefers-color-scheme`)

### Phase 2: Runtime Toggle

**Sequence when user clicks the theme toggle button:**

1. **User clicks** ThemeToggle component's button in the navbar
2. **Component calls** `useTheme().toggleTheme()`
3. **Composable updates** the `theme` ref:
   - If current is `'catalogDark'` → set to `'catalogLight'`
   - If current is `'catalogLight'` → set to `'catalogDark'`
4. **DOM update** — synchronously mutate `document.documentElement.dataset.theme`
5. **Persistence** — write the new theme to `localStorage['theme']`

### Phase 3: CSS Variable Injection

Two parallel processes handle the visual transition:

**Process A: CSS Auto-Switch (Immediate)**
- Tailwind's theme system uses the `[data-theme]` attribute as a selector
- The daisyUI plugin configures two theme blocks:
  ```css
  @plugin "daisyui/theme" {
    name: 'catalogLight';
    --color-base-100: oklch(99% 0.002 90);
    --color-text: oklch(22% 0.006 90);
    --color-primary: oklch(58% 0.13 205);
    ...
  }
  @plugin "daisyui/theme" {
    name: 'catalogDark';
    --color-base-100: oklch(17% 0.006 260);
    --color-text: oklch(92% 0.005 260);
    --color-primary: oklch(72% 0.12 205);
    ...
  }
  ```
- When `[data-theme=catalogLight]` is set, the browser applies the light palette
- When `[data-theme=catalogDark]` is set, the browser applies the dark palette
- **All elements already using Tailwind utilities** (e.g., `text-base-content`, `bg-base-100`, `border-base-content`) automatically re-compute with the new CSS variables
- **No JavaScript needed** — pure CSS scoping

**Process B: Vue Re-render (Eventual)**
- `useTheme()` is a Pinia-like composition function (module-level ref)
- When `theme.value` is mutated, Vue's reactivity system notifies all subscribers
- Computed property `isDark` re-evaluates
- Any component using `isDark` or `theme` re-renders
- Components conditionally render icons, badges, or className modifiers based on `isDark`
- Example:
  ```typescript
  <ThemeIcon :is-dark="isDark" />
  <span :class="isDark ? 'text-yellow-400' : 'text-yellow-600'">
    Mode: {{ isDark ? 'Dark' : 'Light' }}
  </span>
  ```

**Combined Effect:**
- CSS variable colors switch instantly (~1ms)
- Vue component re-renders follow within the same frame (~10-50ms)
- User perceives a smooth, single-frame theme transition

### Phase 4: Persistence

The new theme is saved to `localStorage['theme']` immediately, so:
- If the user closes the tab and returns later → theme is restored
- If the user reloads the page → the bootstrap script reads localStorage and applies the theme before Vue loads
- If localStorage is cleared → fallback to system preference again

### CSS Custom Properties Reference

The actual CSS variables injected by the two themes:

**catalogLight (Light Mode)**
- `--color-base-100`: off-white background
- `--color-base-content`: near-black text
- `--color-primary`: blue (#2563eb equivalent)
- `--color-success`: green
- `--color-error`: red
- `--color-warning`: orange/yellow
- `--radius-box`: 0.625rem (consistent corner radius)

**catalogDark (Dark Mode)**
- `--color-base-100`: near-black background
- `--color-base-content`: off-white text
- `--color-primary`: lighter blue
- `--color-success`: lighter green
- `--color-error`: lighter red
- `--color-warning`: lighter yellow
- `--radius-box`: 0.625rem (same corner radius)

### File References

- **Bootstrap:** `index.html` `<script>` tag (runs before app loads)
- **Runtime Toggle:** `src/components/ThemeToggle.vue`
- **Composable:** `src/composables/useTheme.ts`
- **CSS Themes:** `src/style.css` (Tailwind + daisyUI themes)

### Browser Support

- `localStorage` — all modern browsers
- `prefers-color-scheme` media query — all modern browsers
- `document.documentElement.dataset` — all modern browsers
- CSS custom properties — all modern browsers
- `@supports` fallback (if needed) — all modern browsers

No polyfills required for the target browsers (Chrome, Safari, Firefox, Edge).

