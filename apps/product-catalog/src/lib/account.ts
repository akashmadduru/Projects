/**
 * Pure helpers behind the account dashboard: identity, profile completeness and order totals
 */

import type { OrderSummary } from '../types'

export const AVATAR_HUES = [
  'vivid-teal',
  'vivid-sky',
  'vivid-violet',
  'vivid-pink',
  'vivid-coral',
  'vivid-amber',
  'vivid-lime',
] as const
export type AvatarHue = (typeof AVATAR_HUES)[number]

/** Server limits (UpdateProfileRequest): display name 120 characters, phone 32. */
export const DISPLAY_NAME_MAX = 120
export const PHONE_MAX = 32

/** The name shown for a user: their display name, else the part of the email before the "@". */
export function displayNameOf(user: { displayName?: string | null; email: string }): string {
  const name = user.displayName?.trim()
  return name || user.email.split('@')[0] || user.email
}

/** Up to two initials, from the name when there is one and from the email otherwise. */
export function initialsOf(name: string | null | undefined, email: string): string {
  const words = (name ?? '').trim().split(/\s+/).filter(Boolean)
  if (words.length >= 2) return `${words[0]![0]}${words[words.length - 1]![0]}`.toUpperCase()
  if (words.length === 1) return words[0]!.slice(0, 2).toUpperCase()
  return (email.trim()[0] ?? '?').toUpperCase()
}

/** Stable color for a seed, so a person keeps the same avatar color everywhere. */
export function avatarHue(seed: string): AvatarHue {
  let hash = 0
  for (const char of seed) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  return AVATAR_HUES[hash % AVATAR_HUES.length]!
}

export function greeting(date: Date = new Date()): string {
  const hour = date.getHours()
  if (hour < 5) return 'Burning the midnight oil'
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

export interface CompletenessStep {
  key: 'name' | 'phone' | 'email' | 'address'
  label: string
  hint: string
  done: boolean
  /** Where the user can complete this step. */
  to: string
}

export interface Completeness {
  percent: number
  completed: number
  total: number
  steps: CompletenessStep[]
}

interface CompletenessInput {
  displayName: string | null | undefined
  phone: string | null | undefined
  emailVerified: boolean
  /** null while unknown (still loading, or the address service failed). */
  addressCount: number | null
}

/**
 * How far along the profile is. An unknown address count leaves that step out of the percentage
 * rather than counting it against the user for a failed request.
 */
export function profileCompleteness(input: CompletenessInput): Completeness {
  const steps: CompletenessStep[] = [
    {
      key: 'name',
      label: 'Add your name',
      hint: 'Shown on orders and in the header.',
      done: !!input.displayName?.trim(),
      to: '/account/profile',
    },
    {
      key: 'phone',
      label: 'Add a phone number',
      hint: 'Lets couriers reach you about deliveries.',
      done: !!input.phone?.trim(),
      to: '/account/profile',
    },
    {
      key: 'email',
      label: 'Verify your email',
      hint: 'Keeps your account recoverable.',
      done: input.emailVerified,
      to: '/account/settings',
    },
    {
      key: 'address',
      label: 'Save a delivery address',
      hint: 'Check out faster next time.',
      done: (input.addressCount ?? 0) > 0,
      to: '/account/addresses',
    },
  ]
  const counted = input.addressCount === null ? steps.filter((s) => s.key !== 'address') : steps
  const completed = counted.filter((s) => s.done).length
  return {
    percent: counted.length === 0 ? 0 : Math.round((completed / counted.length) * 100),
    completed,
    total: counted.length,
    steps,
  }
}

export interface OrderStats {
  orders: number
  placed: number
  cancelled: number
  /** Money spent on non-cancelled orders, one entry per currency. Never mixes currencies. */
  spend: { currency: string; amount: number }[]
  /** False when more orders exist than were loaded, so the figures cover only the latest ones. */
  complete: boolean
}

export function orderStats(loaded: OrderSummary[], totalElements: number): OrderStats {
  const spend = new Map<string, number>()
  let placed = 0
  let cancelled = 0
  for (const order of loaded) {
    if (order.status === 'CANCELLED') {
      cancelled += 1
      continue
    }
    placed += 1
    spend.set(order.currency, (spend.get(order.currency) ?? 0) + Number(order.totalAmount))
  }
  return {
    orders: totalElements,
    placed,
    cancelled,
    spend: [...spend].map(([currency, amount]) => ({ currency, amount: Math.round(amount * 100) / 100 })),
    complete: totalElements <= loaded.length,
  }
}

/** Empty is fine; otherwise 7-15 digits with common separators (the server only caps length). */
export function validatePhone(phone: string): string | undefined {
  const value = phone.trim()
  if (!value) return undefined
  if (value.length > PHONE_MAX) return 'Phone number is too long.'
  if (!/^\+?[0-9 ()\-.]+$/.test(value)) return 'Use digits, spaces and + ( ) - only.'
  const digits = value.replace(/\D/g, '').length
  if (digits < 7 || digits > 15) return 'Enter a valid phone number (7-15 digits).'
  return undefined
}
