/**
 * Cleans a product description for display.
 *
 * Some catalog data was generated from templates and stored with unfilled placeholders:
 * `{b}` brand, `{n}` product noun, `{m}` material, `{c}` colour. We fill them from the product's own
 * fields (and drop any we cannot fill) so shoppers never see raw braces, and remove sentences that
 * repeat verbatim.
 */
export interface DescriptionSource {
  name: string
  brand?: string | null
  material?: string | null
  color?: string | null
  description?: string | null
}

const PLACEHOLDER = /\{[bnmc]\}/i

export function cleanDescription(p: DescriptionSource): string {
  let text = p.description ?? ''
  if (!text) return ''

  if (PLACEHOLDER.test(text)) {
    const brand = p.brand?.trim() ?? ''
    // "{b} {n}" is "Brand Noun [Modifier]", which is exactly the product name.
    text = text.replace(/\{b\}\s*\{n\}/gi, p.name)
    const noun = brand && p.name.toLowerCase().startsWith(brand.toLowerCase()) ? p.name.slice(brand.length).trim() : p.name
    text = text
      .replace(/\{b\}/gi, brand || p.name)
      .replace(/\{n\}/gi, noun)
      .replace(/\{m\}/gi, p.material?.trim().toLowerCase() || 'quality materials')
      .replace(/\{c\}/gi, p.color?.trim().toLowerCase() || 'a refined')
  }

  // De-duplicate repeated sentences, keeping the first occurrence.
  const seen = new Set<string>()
  const sentences = text.match(/[^.!?]+[.!?]+(\s+|$)|[^.!?]+$/g) ?? [text]
  return sentences
    .filter((s) => {
      const key = s.trim().toLowerCase()
      if (!key || seen.has(key)) return false
      seen.add(key)
      return true
    })
    .join('')
    .trim()
}
