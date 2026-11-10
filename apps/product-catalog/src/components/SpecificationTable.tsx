/**
 * Product specifications table component
 */

interface Props {
  specifications?: Record<string, unknown> | null
}

export function SpecificationTable({ specifications }: Props) {
  if (!specifications || typeof specifications !== 'object' || Object.keys(specifications).length === 0) {
    return (
      <div className="text-sm text-on-surface-muted text-center py-8">
        No specifications available
      </div>
    )
  }

  const entries = Object.entries(specifications).filter(([, value]) => value !== null && value !== undefined)

  if (entries.length === 0) {
    return (
      <div className="text-sm text-on-surface-muted text-center py-8">
        No specifications available
      </div>
    )
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <tbody>
          {entries.map(([key, value]) => (
            <tr key={key} className="border-b border-outline/10 hover:bg-surface-variant/50 transition-colors">
              <td className="py-3 px-4 font-medium text-on-surface-muted bg-surface-variant/30 w-1/3">
                {formatSpecKey(key)}
              </td>
              <td className="py-3 px-4 text-on-surface">
                {formatSpecValue(value)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function formatSpecKey(key: string): string {
  return key
    .split(/(?=[A-Z])|[-_\s]/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(' ')
}

function formatSpecValue(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (Array.isArray(value)) return value.join(', ')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
