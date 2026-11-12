/** Recent search terms for the autocomplete dropdown (last 6, localStorage). */

const KEY = 'recent-searches'
const MAX = 6

export function getRecentSearches(): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? '[]') as unknown
    return Array.isArray(raw) ? raw.filter((v): v is string => typeof v === 'string') : []
  } catch {
    return []
  }
}

export function addRecentSearch(term: string): void {
  const t = term.trim()
  if (t.length < 2) return
  try {
    const next = [t, ...getRecentSearches().filter((v) => v.toLowerCase() !== t.toLowerCase())].slice(0, MAX)
    localStorage.setItem(KEY, JSON.stringify(next))
  } catch {
    // ignore
  }
}

export function clearRecentSearches(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // ignore
  }
}
