/**
 * "Retro lights" preference (marquee bulbs, neon flicker, searchlights). On by default; the
 * choice is stored per browser and applied as `lights-off` on <html>, which style.css uses to
 * hide/freeze every light effect. prefers-reduced-motion is honoured separately in CSS.
 */

import { useSyncExternalStore } from 'react'

const KEY = 'retro_lights'
const listeners = new Set<() => void>()

function read(): boolean {
  try {
    return localStorage.getItem(KEY) !== 'off'
  } catch {
    return true
  }
}

function apply(on: boolean): void {
  if (typeof document !== 'undefined') document.documentElement.classList.toggle('lights-off', !on)
}

let current = typeof window === 'undefined' ? true : read()
apply(current)

export function setRetroLights(on: boolean): void {
  current = on
  try {
    localStorage.setItem(KEY, on ? 'on' : 'off')
  } catch {
    // Storage unavailable: the preference lasts for this page view only.
  }
  apply(on)
  listeners.forEach((l) => l())
}

export function useRetroLights(): [boolean, (on: boolean) => void] {
  const on = useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    () => current,
    () => true,
  )
  return [on, setRetroLights]
}
