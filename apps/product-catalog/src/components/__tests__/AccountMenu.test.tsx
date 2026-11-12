import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { AccountMenu } from '../AccountMenu'
import { useAuthStore } from '../../stores'
import type { AuthUser } from '../../types'

const user: AuthUser = {
  id: 'u1',
  email: 'ada@example.com',
  emailVerified: true,
  displayName: 'Ada Lovelace',
  status: 'ACTIVE',
  roles: ['VIEWER'],
  createdAt: '2026-01-05T00:00:00Z',
}

const logout = vi.fn(() => Promise.resolve())

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderMenu() {
  return render(
    <MemoryRouter initialEntries={['/shop']}>
      <AccountMenu />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AccountMenu', () => {
  beforeEach(() => {
    logout.mockClear()
    useAuthStore.setState({ status: 'authenticated', user, logout })
  })

  it('offers sign in to anonymous visitors', () => {
    useAuthStore.setState({ status: 'anonymous', user: null })
    renderMenu()

    expect(screen.getByRole('link', { name: /sign in/i })).toHaveAttribute('href', '/login')
  })

  it('renders nothing while the session is still being restored', () => {
    useAuthStore.setState({ status: 'unknown', user: null })
    const { container } = renderMenu()

    expect(container.querySelector('button, a')).toBeNull()
  })

  it('opens a menu with the person, and Profile leads to the dashboard', () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Ada Lovelace' }))

    const menu = screen.getByRole('menu', { name: 'Account' })
    expect(menu).toHaveTextContent('Ada Lovelace')
    expect(menu).toHaveTextContent('ada@example.com')

    fireEvent.click(screen.getByRole('menuitem', { name: /Profile/ }))

    expect(screen.getByTestId('where')).toHaveTextContent('/account')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('links every account section', () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: /Account menu/ }))

    const hrefs = screen.getAllByRole('menuitem').map((item) => item.getAttribute('href'))
    expect(hrefs).toEqual(['/account', '/account/orders', '/account/addresses', '/wishlist', '/account/settings', null])
  })

  it('closes on Escape and reflects aria-expanded', () => {
    renderMenu()
    const trigger = screen.getByRole('button', { name: /Account menu/ })
    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('moves focus through the items with the arrow keys', () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: /Account menu/ }))
    const items = screen.getAllByRole('menuitem')

    fireEvent.keyDown(document, { key: 'ArrowDown' })
    expect(items[0]).toHaveFocus()
    fireEvent.keyDown(document, { key: 'ArrowUp' })
    expect(items[items.length - 1]).toHaveFocus()
  })

  it('signs out and returns home', async () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: /Account menu/ }))

    fireEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }))

    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/'))
  })

  it('falls back to the email name when there is no display name', () => {
    useAuthStore.setState({ user: { ...user, displayName: '' } })
    renderMenu()

    expect(screen.getByRole('button', { name: 'Account menu for ada' })).toBeInTheDocument()
  })
})
