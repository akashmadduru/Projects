import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const apiFetch = vi.hoisted(() => vi.fn())
vi.mock('../lib/http', () => ({ apiFetch }))

import { PaymentApiClient } from './paymentApi'

describe('PaymentApiClient.config', () => {
  beforeEach(() => {
    apiFetch.mockReset()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-17T10:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('reuses one request for every caller within five minutes', async () => {
    apiFetch.mockResolvedValue({ mode: 'STRIPE', providers: [] })
    const client = new PaymentApiClient()

    await client.config()
    await client.config()
    vi.advanceTimersByTime(4 * 60_000)
    await client.config()

    expect(apiFetch).toHaveBeenCalledTimes(1)
  })

  it('refetches once the cache is five minutes old', async () => {
    apiFetch.mockResolvedValue({ mode: 'STRIPE', providers: [] })
    const client = new PaymentApiClient()

    await client.config()
    vi.advanceTimersByTime(5 * 60_000)
    await client.config()

    expect(apiFetch).toHaveBeenCalledTimes(2)
  })

  it('does not cache a failure', async () => {
    apiFetch.mockRejectedValueOnce(new Error('down')).mockResolvedValue({ mode: 'RAZORPAY', providers: [] })
    const client = new PaymentApiClient()

    await expect(client.config()).rejects.toThrow('down')
    await expect(client.config()).resolves.toMatchObject({ mode: 'RAZORPAY' })
    expect(apiFetch).toHaveBeenCalledTimes(2)
  })
})
