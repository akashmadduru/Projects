/**
 * Related products carousel
 */

import { Carousel } from './Carousel'
import { ProductCard } from './ProductCard'
import type { Product } from '../types'

interface Props {
  products: Product[]
  title?: string
}

export function RelatedProducts({ products, title = 'Related Products' }: Props) {
  if (products.length === 0) return null

  return (
    <section aria-label={title} className="space-y-4">
      <h2 className="font-display text-2xl font-extrabold text-on-surface">{title}</h2>
      <Carousel label={title} itemMinWidth={256} gap={24} className="px-1">
        {products.map((product) => (
          <div key={product.id} className="h-full">
            <ProductCard product={product} />
          </div>
        ))}
      </Carousel>
    </section>
  )
}
