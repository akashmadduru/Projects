/**
 * "Compare" toggle for cards and the product page. Max 4 products; the tray shows the selection.
 */

import { useCompareStore } from '../stores/compareStore'
import { useUiStore } from '../stores'
import type { Product } from '../types'

export function CompareButton({ product, className = '' }: { product: Product; className?: string }) {
  const selected = useCompareStore((s) => s.items.some((i) => i.id === product.id))
  const add = useCompareStore((s) => s.add)
  const remove = useCompareStore((s) => s.remove)
  const notify = useUiStore((s) => s.notify)

  const onClick = (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    if (selected) {
      remove(product.id)
      return
    }
    const ok = add({ id: product.id, slug: product.slug, name: product.name, image: product.images[0] })
    if (!ok) notify('You can compare up to 4 products. Remove one first.')
  }

  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className={`press relative z-10 inline-flex items-center gap-1.5 rounded-full border-2 px-3 py-1 text-xs font-bold ${
        selected ? 'border-ink bg-vivid-teal text-ink' : 'border-outline text-on-surface-muted hover:border-ink hover:text-on-surface'
      } ${className}`}
    >
      <span
        aria-hidden="true"
        className={`flex h-3.5 w-3.5 items-center justify-center rounded border-2 border-current text-[9px] leading-none`}
      >
        {selected ? '✓' : ''}
      </span>
      Compare
      <span className="sr-only">{` ${product.name}`}</span>
    </button>
  )
}
