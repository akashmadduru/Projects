import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ProfilePage } from '../ProfilePage'
import { userApi } from '../../../services/userApi'
import { useAuthStore, useUiStore } from '../../../stores'
import type { AuthUser, UserProfile } from '../../../types'

vi.mock('../../../services/userApi', () => ({
  userApi: { getProfile: vi.fn(), listAddresses: vi.fn(), updateProfile: vi.fn() },
}))

const user: AuthUser = {
  id: 'u1',
  email: 'ada@example.com',
  emailVerified: true,
  displayName: 'Ada Lovelace',
  status: 'ACTIVE',
  roles: ['VIEWER'],
  createdAt: '2026-01-05T00:00:00Z',
}

const profile: UserProfile = {
  userId: '9f1c2d3e-0000-4000-8000-000000000001',
  email: 'ada@example.com',
  displayName: 'Ada Lovelace',
  phone: '+1 555 123 4567',
  createdAt: '2026-01-05T00:00:00Z',
}

const renderPage = () =>
  render(
    <MemoryRouter>
      <ProfilePage />
    </MemoryRouter>,
  )

const nameInput = () => screen.getByLabelText('Display name')
const phoneInput = () => screen.getByLabelText('Phone number')
const saveButton = () => screen.getByRole('button', { name: /Save changes|Saving/ })

describe('ProfilePage', () => {
  beforeEach(() => {
    vi.mocked(userApi.getProfile).mockResolvedValue(profile)
    vi.mocked(userApi.listAddresses).mockResolvedValue([])
    vi.mocked(userApi.updateProfile).mockReset()
    useAuthStore.setState({ status: 'authenticated', user })
    useUiStore.setState({ toasts: [] })
  })

  it('loads the saved values and starts with nothing to save', async () => {
    renderPage()

    await waitFor(() => expect(nameInput()).toHaveValue('Ada Lovelace'))
    expect(phoneInput()).toHaveValue('+1 555 123 4567')
    expect(screen.getByLabelText('Email address')).toBeDisabled()
    expect(screen.getByText('All changes saved')).toBeInTheDocument()
    expect(saveButton()).toBeDisabled()
  })

  it('tracks unsaved changes and offers to discard them', async () => {
    renderPage()
    await waitFor(() => expect(nameInput()).toHaveValue('Ada Lovelace'))

    fireEvent.change(nameInput(), { target: { value: 'Ada King' } })

    expect(screen.getByText('You have unsaved changes')).toBeInTheDocument()
    expect(saveButton()).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Discard' }))

    expect(nameInput()).toHaveValue('Ada Lovelace')
    expect(screen.getByText('All changes saved')).toBeInTheDocument()
  })

  it('previews the name as you type', async () => {
    renderPage()
    await waitFor(() => expect(nameInput()).toHaveValue('Ada Lovelace'))

    fireEvent.change(nameInput(), { target: { value: 'Grace Hopper' } })

    expect(screen.getByRole('complementary', { name: 'Profile preview' })).toHaveTextContent('Grace Hopper')
  })

  it('validates the name and phone before saving', async () => {
    renderPage()
    await waitFor(() => expect(nameInput()).toHaveValue('Ada Lovelace'))

    fireEvent.change(nameInput(), { target: { value: '   ' } })
    fireEvent.blur(nameInput())
    fireEvent.change(phoneInput(), { target: { value: 'call me' } })
    fireEvent.blur(phoneInput())

    expect(screen.getByText('Please enter your name.')).toBeInTheDocument()
    expect(screen.getByText(/Use digits, spaces/)).toBeInTheDocument()
    expect(nameInput()).toHaveAttribute('aria-invalid', 'true')

    fireEvent.click(saveButton())

    expect(userApi.updateProfile).not.toHaveBeenCalled()
  })

  it('saves trimmed values, updates the session name and confirms', async () => {
    vi.mocked(userApi.updateProfile).mockResolvedValue({ ...profile, displayName: 'Ada King', phone: null })
    renderPage()
    await waitFor(() => expect(nameInput()).toHaveValue('Ada Lovelace'))

    fireEvent.change(nameInput(), { target: { value: '  Ada King ' } })
    fireEvent.change(phoneInput(), { target: { value: '   ' } })
    fireEvent.click(saveButton())

    await waitFor(() => expect(userApi.updateProfile).toHaveBeenCalledWith({ displayName: 'Ada King', phone: null }))
    await waitFor(() => expect(useAuthStore.getState().user?.displayName).toBe('Ada King'))
    expect(useUiStore.getState().toasts[0]?.message).toBe('Profile saved')
    await waitFor(() => expect(screen.getByText('All changes saved')).toBeInTheDocument())
  })

  it('shows the server error and keeps the edits when saving fails', async () => {
    vi.mocked(userApi.updateProfile).mockRejectedValue(new Error('offline'))
    renderPage()
    await waitFor(() => expect(nameInput()).toHaveValue('Ada Lovelace'))

    fireEvent.change(nameInput(), { target: { value: 'Ada King' } })
    fireEvent.click(saveButton())

    expect(await screen.findByText(/Could not reach the server/)).toBeInTheDocument()
    expect(nameInput()).toHaveValue('Ada King')
    expect(saveButton()).toBeEnabled()
  })

  it('caps the inputs at the server limits', async () => {
    renderPage()
    await waitFor(() => expect(nameInput()).toHaveValue('Ada Lovelace'))

    expect(nameInput()).toHaveAttribute('maxlength', '120')
    expect(phoneInput()).toHaveAttribute('maxlength', '32')
  })

  it('shows account details with the member-since date', async () => {
    renderPage()

    expect(await screen.findByText('Member since')).toBeInTheDocument()
    expect(screen.getByText(profile.userId)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy account ID' })).toBeInTheDocument()
  })
})
