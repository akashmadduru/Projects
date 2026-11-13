/**
 * Auth API client (auth-service via gateway)
 */

import { apiFetch } from '../lib/http'
import type { AuthSession, AuthUser, LoginRequest, LoginResult, RegisterRequest, TokenResponse, TwoFactorSetup } from '../types'

export class AuthApiClient {
  login(request: LoginRequest): Promise<LoginResult> {
    return apiFetch<LoginResult>('/api/auth/login', { method: 'POST', body: request })
  }

  register(request: RegisterRequest): Promise<AuthUser> {
    return apiFetch<AuthUser>('/api/auth/register', { method: 'POST', body: request })
  }

  logout(): Promise<void> {
    return apiFetch<void>('/api/auth/logout', { method: 'POST' })
  }

  forgotPassword(email: string): Promise<void> {
    return apiFetch<void>('/api/auth/password/forgot', { method: 'POST', body: { email } })
  }

  resetPassword(token: string, newPassword: string): Promise<void> {
    return apiFetch<void>('/api/auth/password/reset', { method: 'POST', body: { token, newPassword } })
  }

  verifyEmail(token: string): Promise<void> {
    return apiFetch<void>('/api/auth/email/verify', { method: 'POST', body: { token } })
  }

  resendVerification(): Promise<void> {
    return apiFetch<void>('/api/auth/email/verify/resend', { method: 'POST' })
  }

  requestOtp(phone: string): Promise<void> {
    return apiFetch<void>('/api/auth/otp/request', { method: 'POST', body: { phone } })
  }

  verifyOtp(phone: string, code: string): Promise<LoginResult> {
    return apiFetch<LoginResult>('/api/auth/otp/verify', { method: 'POST', body: { phone, code } })
  }

  addPhone(phone: string): Promise<void> {
    return apiFetch<void>('/api/auth/me/phone', { method: 'POST', body: { phone } })
  }

  verifyPhone(code: string): Promise<void> {
    return apiFetch<void>('/api/auth/me/phone/verify', { method: 'POST', body: { code } })
  }

  verifyMfa(mfaToken: string, input: { code: string } | { recoveryCode: string }): Promise<TokenResponse> {
    return apiFetch<TokenResponse>('/api/auth/2fa/verify', { method: 'POST', body: { mfaToken, ...input } })
  }

  twoFactorSetup(): Promise<TwoFactorSetup> {
    return apiFetch<TwoFactorSetup>('/api/auth/2fa/setup', { method: 'POST' })
  }

  twoFactorEnable(code: string): Promise<{ recoveryCodes: string[] }> {
    return apiFetch<{ recoveryCodes: string[] }>('/api/auth/2fa/enable', { method: 'POST', body: { code } })
  }

  twoFactorDisable(input: { code?: string; recoveryCode?: string; password: string }): Promise<void> {
    return apiFetch<void>('/api/auth/2fa/disable', { method: 'POST', body: input })
  }

  sessions(): Promise<AuthSession[]> {
    return apiFetch<AuthSession[]>('/api/auth/sessions')
  }

  revokeSession(familyId: string): Promise<void> {
    return apiFetch<void>(`/api/auth/sessions/${familyId}`, { method: 'DELETE' })
  }

  revokeOtherSessions(): Promise<void> {
    return apiFetch<void>('/api/auth/sessions/revoke-others', { method: 'POST' })
  }

  me(): Promise<AuthUser> {
    return apiFetch<AuthUser>('/api/auth/me')
  }
}

export const authApi = new AuthApiClient()

/**
 * Where Google sign-in starts. A top-level navigation, never a fetch: the OAuth2 flow redirects
 * cross-origin to Google and back. It starts DIRECTLY at auth-service (not via the gateway): Spring
 * builds the Google callback from the request host, and behind the gateway that is the container IP,
 * which Google rejects. Starting at localhost:8081 yields http://localhost:8081/login/oauth2/code/google,
 * the URI registered in the Google console. Cookies are port-agnostic, so the refresh cookie set
 * there is still sent to this app's /api/auth/refresh. auth-service accepts `redirect_uri` only if it
 * exactly matches its allow-list (AUTH_ALLOWED_REDIRECT_URIS), otherwise it falls back to its
 * default. No token is ever put in the URL: on return, the refresh cookie is already set and the
 * app's normal bootstrap obtains the first access token.
 */
export function googleSignInUrl(): string {
  const base = (import.meta.env.VITE_AUTH_BASE_URL ?? 'http://localhost:8081').replace(/\/+$/, '')
  const callback = `${window.location.origin}/auth/callback`
  return `${base}/oauth2/authorization/google?redirect_uri=${encodeURIComponent(callback)}`
}
