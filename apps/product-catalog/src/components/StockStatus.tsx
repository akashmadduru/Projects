/**
 * StockStatus component - displays product stock availability
 */

import React from 'react'

interface Props {
  quantity: number
  threshold?: number
}

function StockStatusComponent({ quantity, threshold = 20 }: Props) {
  const isOutOfStock = quantity === 0
  const isLowStock = quantity > 0 && quantity <= threshold

  if (isOutOfStock) {
    return (
      <span className="inline-block px-3 py-1 rounded-full text-xs font-medium bg-on-surface text-surface dark:bg-surface dark:text-on-surface">
        Out of Stock
      </span>
    )
  }

  if (isLowStock) {
    return (
      <span className="inline-block px-3 py-1 rounded-full text-xs font-medium bg-outline/50 text-on-surface dark:bg-outline/40 dark:text-on-surface">
        Low Stock ({quantity})
      </span>
    )
  }

  return (
    <span className="inline-block px-3 py-1 rounded-full text-xs font-medium bg-surface-variant text-on-surface dark:bg-surface-variant dark:text-on-surface border border-outline/20">
      In Stock ({quantity})
    </span>
  )
}

export const StockStatus = React.memo(StockStatusComponent)
