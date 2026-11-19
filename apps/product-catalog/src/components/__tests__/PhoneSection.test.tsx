/**
 * PhoneSection: neutral hint once the code field shows (O-01); OTP errors keep the existing message.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PhoneSection } from '../account/PhoneSection'
import { authApi } from '../../services/authApi'

vi.mock('../../services/authApi', () => ({
  authApi: { addPhone: vi.fn(), verifyPhone: vi.fn() },
}))

async function sendCode() {
  render(<PhoneSection />)
  await userEvent.type(screen.getByLabelText('Mobile number'), '+919876543210')
  await userEvent.click(screen.getByRole('button', { name: 'Send code' }))
}

describe('PhoneSection', () => {
  beforeEach(() => {
    vi.mocked(authApi.addPhone).mockReset().mockResolvedValue(undefined as never)
    vi.mocked(authApi.verifyPhone).mockReset()
  })

  it('shows a neutral hint once the code field is shown', async () => {
    await sendCode()
    const hint = await screen.findByText('If the code does not arrive, check the number.')
    expect(hint.textContent).not.toMatch(/already|in use|taken|verified/i)
  })

  it('shows the existing message for AUTH_OTP_INVALID', async () => {
    const err = Object.assign(new Error('x'), { name: 'ApiError', status: 401, problem: { code: 'AUTH_OTP_INVALID' } })
    vi.mocked(authApi.verifyPhone).mockRejectedValue(err)
    await sendCode()
    await userEvent.type(await screen.findByLabelText('6-digit code'), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Verify number' }))
    expect(await screen.findByText(/That code is incorrect or has expired/)).toBeTruthy()
  })
})
