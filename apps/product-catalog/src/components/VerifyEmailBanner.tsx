/**
 * Slim banner for signed-in users whose email is not verified yet, with a Resend action.
 */

import { useState } from 'react'
import { secondaryButtonClass } from './Form'
import { errorMessage } from '../lib/errors'
import { authApi } from '../services/authApi'
import { useAuthStore, useUiStore } from '../stores'

export function VerifyEmailBanner() {
  const user = useAuthStore((s) => s.user)
  const notify = useUiStore((s) => s.notify)
  const [busy, setBusy] = useState(false)

  // Only shown when the backend explicitly reports false (undefined = older backend, stay quiet).
  if (!user || user.emailVerified !== false) return null

  const resend = async () => {
    setBusy(true)
    try {
      await authApi.resendVerification()
      notify('Verification email sent')
    } catch (err) {
      notify(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div role="status" className="border-b-2 border-ink bg-vivid-amber/40 px-4 py-2 text-sm text-on-surface">
      <div className="mx-auto flex max-w-[1400px] flex-wrap items-center justify-between gap-2">
        <span className="font-semibold">Verify your email: we sent a link to {user.email}.</span>
        <button type="button" onClick={() => void resend()} disabled={busy} className={secondaryButtonClass}>
          {busy ? 'Sending...' : 'Resend email'}
        </button>
      </div>
    </div>
  )
}
