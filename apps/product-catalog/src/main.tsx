/**
 * Application entry point
 */

import React from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { useAuthStore } from './stores'
import './lib/retroLights' // applies the saved "lights off" preference before first paint
import './style.css'


const rootElement = document.getElementById('root')
if (!rootElement) {
  throw new Error('Root element not found')
}

// Restore a session from the refresh cookie (single-flight, safe under StrictMode).
void useAuthStore.getState().bootstrap()

const root = createRoot(rootElement)
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
