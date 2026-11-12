import { describe, it, expect } from 'vitest'
import {
  AVATAR_HUES,
  avatarHue,
  displayNameOf,
  greeting,
  initialsOf,
  orderStats,
  profileCompleteness,
  validatePhone,
} from './account'
import type { OrderSummary } from '../types'

describe('identity helpers', () => {
  it('falls back from display name to the email local part', () => {
    expect(displayNameOf({ displayName: ' Ada Lovelace ', email: 'ada@x.io' })).toBe('Ada Lovelace')
    expect(displayNameOf({ displayName: '  ', email: 'ada@x.io' })).toBe('ada')
    expect(displayNameOf({ displayName: null, email: 'grace@x.io' })).toBe('grace')
  })

  it('builds initials from first and last word, one word, or the email', () => {
    expect(initialsOf('Ada Lovelace', 'a@x.io')).toBe('AL')
    expect(initialsOf('Grace Brewster Hopper', 'g@x.io')).toBe('GH')
    expect(initialsOf('plato', 'p@x.io')).toBe('PL')
    expect(initialsOf('', 'zed@x.io')).toBe('Z')
    expect(initialsOf(undefined, '')).toBe('?')
  })

  it('gives the same avatar color for the same seed, always from the palette', () => {
    expect(avatarHue('ada@x.io')).toBe(avatarHue('ada@x.io'))
    expect(AVATAR_HUES).toContain(avatarHue('someone@example.com'))
    expect(new Set(['a', 'bb', 'ccc', 'dddd', 'eeeee', 'ffffff'].map(avatarHue)).size).toBeGreaterThan(1)
  })

  it('greets by time of day', () => {
    expect(greeting(new Date('2026-01-01T09:00:00'))).toBe('Good morning')
    expect(greeting(new Date('2026-01-01T14:00:00'))).toBe('Good afternoon')
    expect(greeting(new Date('2026-01-01T20:00:00'))).toBe('Good evening')
    expect(greeting(new Date('2026-01-01T02:00:00'))).toBe('Burning the midnight oil')
  })
})

describe('profileCompleteness', () => {
  const base = { displayName: 'Ada', phone: '+1 555 123 4567', emailVerified: true, addressCount: 1 }

  it('is 100% when every step is done', () => {
    const result = profileCompleteness(base)
    expect(result.percent).toBe(100)
    expect(result.steps.every((s) => s.done)).toBe(true)
  })

  it('counts each missing step and points to where it is fixed', () => {
    const result = profileCompleteness({ displayName: '', phone: null, emailVerified: false, addressCount: 0 })
    expect(result.percent).toBe(0)
    expect(result.steps.map((s) => [s.key, s.to])).toEqual([
      ['name', '/account/profile'],
      ['phone', '/account/profile'],
      ['email', '/account/settings'],
      ['address', '/account/addresses'],
    ])
  })

  it('leaves the address step out of the percentage while the count is unknown', () => {
    const result = profileCompleteness({ ...base, addressCount: null })
    expect(result.total).toBe(3)
    expect(result.percent).toBe(100)
  })

  it('treats whitespace-only values as missing', () => {
    expect(profileCompleteness({ ...base, displayName: '   ', phone: '  ' }).completed).toBe(2)
  })
})

describe('orderStats', () => {
  const order = (id: string, status: OrderSummary['status'], total: number, currency = 'USD'): OrderSummary => ({
    id,
    status,
    currency,
    totalAmount: total,
    placedAt: '2026-01-01T00:00:00Z',
    itemCount: 1,
  })

  it('totals spend per currency and ignores cancelled orders', () => {
    const stats = orderStats(
      [order('1', 'PLACED', 10.1), order('2', 'PLACED', 20.2), order('3', 'CANCELLED', 99), order('4', 'PLACED', 5, 'EUR')],
      4,
    )

    expect(stats).toMatchObject({ orders: 4, placed: 3, cancelled: 1, complete: true })
    expect(stats.spend).toEqual([
      { currency: 'USD', amount: 30.3 },
      { currency: 'EUR', amount: 5 },
    ])
  })

  it('flags partial data when more orders exist than were loaded', () => {
    expect(orderStats([order('1', 'PLACED', 10)], 120)).toMatchObject({ orders: 120, complete: false })
  })

  it('handles no orders', () => {
    expect(orderStats([], 0)).toEqual({ orders: 0, placed: 0, cancelled: 0, spend: [], complete: true })
  })

  it('accepts amounts the API serialises as strings', () => {
    const stats = orderStats([{ ...order('1', 'PLACED', 0), totalAmount: '12.50' as unknown as number }], 1)
    expect(stats.spend).toEqual([{ currency: 'USD', amount: 12.5 }])
  })
})

describe('validatePhone', () => {
  it('allows empty and common formats', () => {
    expect(validatePhone('')).toBeUndefined()
    expect(validatePhone('  ')).toBeUndefined()
    expect(validatePhone('+1 (555) 123-4567')).toBeUndefined()
    expect(validatePhone('020 7946 0958')).toBeUndefined()
  })

  it('rejects letters, too-short and too-long numbers', () => {
    expect(validatePhone('call me')).toMatch(/digits/)
    expect(validatePhone('12345')).toMatch(/7-15/)
    expect(validatePhone('1'.repeat(16))).toMatch(/7-15/)
    expect(validatePhone('1'.repeat(33))).toMatch(/too long/)
  })
})
