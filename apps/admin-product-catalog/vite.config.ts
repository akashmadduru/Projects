import { fileURLToPath, URL } from 'node:url';

import tailwindcss from '@tailwindcss/vite';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 5173 is not a preference: the gateway's CORS allow-list and auth-service's
    // OAuth2 redirect allow-list both name http://localhost:5173 exactly, and
    // allow-credentials means the origin can never be a wildcard. strictPort makes
    // a taken port fail loudly instead of silently moving to 5174 and producing
    // CORS errors that point nowhere near the cause.
    port: 5173,
    strictPort: true,
  },
});
