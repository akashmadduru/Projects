/** "5 min ago" style timestamp for notification rows. */
export function fmtRelative(iso: string): string {
  const diff = Date.now() - Date.parse(iso)
  if (Number.isNaN(diff)) return ''
  const mins = Math.floor(diff / 60_000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} h ago`
  const days = Math.floor(hours / 24)
  return days < 7 ? `${days} d ago` : new Date(iso).toLocaleDateString()
}
