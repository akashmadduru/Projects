import type { Product } from '../types'

/** The price a customer pays: salePrice when present and positive, otherwise the list price. */
export function effectivePrice(product: Pick<Product, 'priceAmount' | 'salePrice'>): number {
  return product.salePrice && product.salePrice > 0 ? product.salePrice : product.priceAmount
}
