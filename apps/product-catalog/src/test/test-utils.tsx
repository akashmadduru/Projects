/**
 * Test utilities and custom render function
 */

import React from 'react'
import { render as rtlRender } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'
import { vi } from 'vitest'
import type { ReactElement } from 'react'

/**
 * Custom render function that wraps components with necessary providers
 */
function render(ui: ReactElement, options = {}) {
  function Wrapper({ children }: { children: React.ReactNode }) {
    return <BrowserRouter>{children}</BrowserRouter>
  }

  return rtlRender(ui, { wrapper: Wrapper, ...options })
}

/**
 * Mock localStorage for tests
 */
export function createMockLocalStorage() {
  let store: Record<string, string> = {}

  return {
    getItem: (key: string) => store[key] || null,
    setItem: (key: string, value: string) => {
      store[key] = String(value)
    },
    removeItem: (key: string) => {
      delete store[key]
    },
    clear: () => {
      store = {}
    },
  }
}

/**
 * Mock matchMedia for prefers-reduced-motion tests
 */
export function mockMatchMedia(matches: boolean = false) {
  window.matchMedia = vi.fn().mockImplementation((query) => ({
    matches,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }))
}

/**
 * Reset matchMedia to default (no prefers-reduced-motion)
 */
export function resetMatchMedia() {
  window.matchMedia = vi.fn().mockImplementation((query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }))
}

export * from '@testing-library/react'
export { render }
