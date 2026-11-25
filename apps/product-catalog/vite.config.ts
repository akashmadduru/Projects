import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [tailwindcss(), react()],
  css: {
    postcss: './postcss.config.js',
  },
  server: {
    // Pinned (matches `npm run dev --port 3000` and the Docker image): Google sign-in returns to an
    // exact-match allow-listed callback (http://localhost:3000/auth/callback, see AUTH_ALLOWED_REDIRECT_URIS
    // in deploy/local/docker-compose.yml), so silently moving to another port would break it.
    port: 3000,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '/api'),
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    minify: 'terser',
    rollupOptions: {
      output: {
        // TD-P7-18: the framework changes far less often than app code, so it gets its own
        // long-cached chunk; Stripe is only reached from lazy checkout/payment routes.
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (/[\\/]node_modules[\\/](react|react-dom|scheduler|react-router|react-router-dom|@remix-run)[\\/]/.test(id)) {
            return 'react-vendor'
          }
          if (id.includes('@stripe')) return 'stripe'
          return undefined
        },
      },
    },
  },
})
