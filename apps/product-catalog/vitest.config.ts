import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/**
 * Windows: a shell started in `c:\...` (lower-case drive, as VS Code does) gives Vite one spelling
 * of every path and Node another (`C:\...`). Modules then load twice: the setup file's `beforeAll`
 * fails with "reading 'on'" and `vi.mock` stops matching imports (TD-FE-12). Normalising the
 * working directory before Vite starts gives every path one spelling.
 */
if (process.platform === 'win32' && /^[a-z]:/.test(process.cwd())) {
  process.chdir(process.cwd().charAt(0).toUpperCase() + process.cwd().slice(1))
}

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Page tests render whole routes in jsdom; ~3 s each on a loaded machine, so the 5 s default
    // produced false timeouts (OverviewPage) when builds ran alongside.
    testTimeout: 15_000,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
    },
  },
})
