/**
 * Stock status badge component
 */

interface Props {
  status: 'in-stock' | 'low-stock' | 'out-of-stock'
  quantity?: number
  size?: 'sm' | 'md' | 'lg'
}

export function StockBadge({ status, quantity, size = 'md' }: Props) {
  const statusConfig = {
    'in-stock': {
      bg: 'bg-green-50 dark:bg-green-950/30',
      border: 'border-green-200 dark:border-green-900',
      text: 'text-green-700 dark:text-green-400',
      label: 'In Stock',
    },
    'low-stock': {
      bg: 'bg-amber-50 dark:bg-amber-950/30',
      border: 'border-amber-200 dark:border-amber-900',
      text: 'text-amber-700 dark:text-amber-400',
      label: 'Low Stock',
    },
    'out-of-stock': {
      bg: 'bg-red-50 dark:bg-red-950/30',
      border: 'border-red-200 dark:border-red-900',
      text: 'text-red-700 dark:text-red-400',
      label: 'Out of Stock',
    },
  }

  const config = statusConfig[status]

  const sizeClasses = {
    sm: 'text-xs px-2 py-1',
    md: 'text-sm px-2.5 py-1.5',
    lg: 'text-base px-3 py-2',
  }

  return (
    <div className={`inline-flex items-center gap-1.5 font-medium rounded border ${config.bg} ${config.border} ${config.text} ${sizeClasses[size]}`}>
      <span
        className="w-2 h-2 rounded-full"
        style={{
          backgroundColor:
            status === 'in-stock'
              ? 'currentColor'
              : status === 'low-stock'
                ? 'currentColor'
                : 'currentColor',
        }}
      />
      {config.label}
      {quantity !== undefined && status === 'in-stock' && (
        <span className="ml-1 opacity-75">({quantity})</span>
      )}
    </div>
  )
}
