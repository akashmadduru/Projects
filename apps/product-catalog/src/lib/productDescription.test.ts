import { describe, expect, it } from 'vitest'
import { cleanDescription } from './productDescription'

const product = { name: 'Zenta Smartwatch Pro', brand: 'Zenta', material: 'Stainless Steel', color: 'Navy Blue' }

describe('cleanDescription', () => {
  it('fills {b} {n} and {m} and drops the repeated sentence', () => {
    const out = cleanDescription({
      ...product,
      description:
        'Meet the {b} {n}: a thoughtful balance of performance, design and value. Crafted from {m}, it feels solid. Crafted from {m}, it feels solid. Packaged in recyclable materials.',
    })
    expect(out).toBe(
      'Meet the Zenta Smartwatch Pro: a thoughtful balance of performance, design and value. Crafted from stainless steel, it feels solid. Packaged in recyclable materials.',
    )
    expect(out).not.toMatch(/[{}]/)
  })

  it('fills {c} and falls back gracefully when fields are missing', () => {
    expect(cleanDescription({ ...product, description: 'A refined finish in {c} blends in.' })).toBe('A refined finish in navy blue blends in.')
    expect(cleanDescription({ name: 'Thing', description: 'Crafted from {m}.' })).toBe('Crafted from quality materials.')
  })

  it('leaves clean descriptions alone and handles empty ones', () => {
    expect(cleanDescription({ ...product, description: 'Nothing to fix here. Really.' })).toBe('Nothing to fix here. Really.')
    expect(cleanDescription({ ...product, description: null })).toBe('')
  })
})
