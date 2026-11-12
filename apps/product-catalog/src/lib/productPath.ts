/** Canonical storefront path for a product: slug when known, id otherwise (the id route redirects to the slug). */
export function productPath(product: { id: string; slug?: string | null }): string {
  return product.slug ? `/p/${product.slug}` : `/products/${product.id}`
}
