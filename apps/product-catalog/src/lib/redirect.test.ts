/**
 * lib/redirect (TD-TEST-02 review): safeNext accepts only same-origin absolute paths, and the
 * remembered Google post-login destination is sanitised on the way in and consumed on the way out.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { rememberPostLoginRedirect, safeNext, takePostLoginRedirect } from './redirect'

const KEY = 'pc.post-login-redirect'

beforeEach(() => sessionStorage.clear())
afterEach(() => sessionStorage.clear())

describe('safeNext', () => {
  it.each([
    ['/a?b=1', '/a?b=1'],
    ['/', '/'],
    ['/orders/123#top', '/orders/123#top'],
  ])('accepts %j', (input, expected) => {
    expect(safeNext(input)).toBe(expected)
  })

  it.each<[string, string | null | undefined]>([
    ['empty string', ''],
    ['null', null],
    ['undefined', undefined],
    ['relative path', 'a'],
    ['protocol-relative', '//x'],
    ['protocol-relative with path', '//evil.example/x'],
    ['backslash after slash', String.raw`/\x`],
    ['absolute https URL', 'https://x'],
    ['javascript URL', 'javascript:x'],
    ['encoded protocol-relative (not a path start)', '%2F%2Fevil.example'],
  ])('rejects %s and returns the fallback', (_name, input) => {
    expect(safeNext(input)).toBe('/')
    expect(safeNext(input, '/home')).toBe('/home')
  })
})

describe('rememberPostLoginRedirect / takePostLoginRedirect', () => {
  it('stores a safe path and consumes the key when taken', () => {
    rememberPostLoginRedirect('/checkout?x=1')
    expect(sessionStorage.getItem(KEY)).toBe('/checkout?x=1')
    expect(takePostLoginRedirect()).toBe('/checkout?x=1')
    expect(sessionStorage.getItem(KEY)).toBeNull()
    expect(takePostLoginRedirect()).toBe('/')
  })

  it.each(['//evil.example', String.raw`/\evil.example`, 'https://evil.example', 'javascript:alert(1)', ''])(
    'stores the sanitised fallback instead of %j',
    (hostile) => {
      rememberPostLoginRedirect(hostile)
      expect(sessionStorage.getItem(KEY)).toBe('/')
      expect(takePostLoginRedirect()).toBe('/')
    },
  )

  it('re-sanitises a hostile value planted in storage when taking it, and still consumes it', () => {
    sessionStorage.setItem(KEY, '//evil.example')
    expect(takePostLoginRedirect()).toBe('/')
    expect(sessionStorage.getItem(KEY)).toBeNull()
  })
})
