/** Recently viewed product ids, newest first, kept in localStorage (last 20). */

const KEY = 'recently-viewed'
const MAX = 20

export function getRecentlyViewed(): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? '[]') as unknown
    return Array.isArray(raw) ? raw.filter((v): v is string => typeof v === 'string') : []
  } catch {
    return []
  }
}

export function removeRecentlyViewed(id: string): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(getRecentlyViewed().filter((v) => v !== id)))
  } catch {
    // Storage unavailable; nothing to remove.
  }
}

export function clearRecentlyViewed(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // Storage unavailable; nothing to clear.
  }
}

export function pushRecentlyViewed(id: string): void {
  try {
    localStorage.setItem(KEY, JSON.stringify([id, ...getRecentlyViewed().filter((v) => v !== id)].slice(0, MAX)))
  } catch {
    // Storage may be unavailable (private mode); the row is a convenience only.
  }
}
