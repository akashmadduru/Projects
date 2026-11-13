/**
 * Application entry point
 */

import React from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { useAuthStore } from './stores'
import './lib/i18n' // applies the saved language (html lang) before first paint
import './lib/retroLights' // applies the saved "lights off" preference before first paint
import './style.css'


const rootElement = document.getElementById('root')
if (!rootElement) {
  throw new Error('Root element not found')
}

// Restore a session from the refresh cookie (single-flight, safe under StrictMode).
void useAuthStore.getState().bootstrap()

// PWA: offline shell + caching + push share one worker (public/sw.js). Production builds only.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js').catch(() => undefined)
  })
}

const root = createRoot(rootElement)
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
