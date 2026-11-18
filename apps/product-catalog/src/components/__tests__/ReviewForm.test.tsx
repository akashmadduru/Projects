/**
 * ReviewForm: photos that fail to upload after the review is saved can be retried (TD-P7-17).
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ReviewForm } from '../ReviewForm'
import { ratingApi } from '../../services/ratingApi'
import { useUiStore } from '../../stores'
import type { Review } from '../../types'

vi.mock('../../services/ratingApi', () => ({
  ratingApi: { create: vi.fn(), update: vi.fn(), uploadMedia: vi.fn(), deleteMedia: vi.fn() },
}))

const saved = { id: 'r1', ratingValue: 4, version: 0, media: [] } as unknown as Review

function photo(name: string) {
  return new File(['x'], name, { type: 'image/png' })
}

async function fillAndSubmit() {
  const onSaved = vi.fn()
  render(<ReviewForm productId="p1" onSaved={onSaved} />)
  await userEvent.click(screen.getByLabelText(/^4 stars/))
  fireEvent.change(screen.getByLabelText('Choose photos'), { target: { files: [photo('a.png'), photo('b.png')] } })
  await userEvent.click(screen.getByRole('button', { name: 'Submit review' }))
  return onSaved
}

describe('ReviewForm photo upload', () => {
  beforeEach(() => {
    // jsdom has no object URLs; the form previews selected photos with them.
    URL.createObjectURL = vi.fn(() => 'blob:preview')
    vi.mocked(ratingApi.create).mockReset().mockResolvedValue(saved)
    vi.mocked(ratingApi.uploadMedia).mockReset()
    useUiStore.setState({ toasts: [] })
  })

  it('finishes straight away when every photo uploads', async () => {
    vi.mocked(ratingApi.uploadMedia).mockResolvedValue({ id: 'm', url: '/m' } as never)
    const onSaved = await fillAndSubmit()

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved))
    expect(ratingApi.uploadMedia).toHaveBeenCalledTimes(2)
  })

  it('keeps failed photos and retries only those, without saving the review twice', async () => {
    vi.mocked(ratingApi.uploadMedia)
      .mockResolvedValueOnce({ id: 'm1', url: '/m1' } as never)
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ id: 'm2', url: '/m2' } as never)
    const onSaved = await fillAndSubmit()

    expect(await screen.findByText(/1 photo could not be uploaded/)).toBeInTheDocument()
    expect(onSaved).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Submit review' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Retry upload' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved))
    expect(ratingApi.create).toHaveBeenCalledTimes(1)
    expect(vi.mocked(ratingApi.uploadMedia).mock.calls.map(([, f]) => (f as File).name)).toEqual(['a.png', 'b.png', 'b.png'])
  })

  it('can continue without the failed photos', async () => {
    vi.mocked(ratingApi.uploadMedia).mockRejectedValue(new Error('down'))
    const onSaved = await fillAndSubmit()

    await userEvent.click(await screen.findByRole('button', { name: 'Continue without them' }))
    expect(onSaved).toHaveBeenCalledWith(saved)
  })
})
