/**
 * Maps API errors to messages shown to the user
 */

import type { ApiError } from '../types'

export function isApiError(error: unknown): error is ApiError {
  return error instanceof Error && error.name === 'ApiError'
}

export function problemCode(error: unknown): string | undefined {
  if (!isApiError(error)) return undefined
  const code = error.problem['code']
  return typeof code === 'string' ? code : undefined
}

const FRIENDLY: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: 'Incorrect email or password.',
  AUTH_ACCOUNT_LOCKED: 'This account is temporarily locked after too many failed attempts. Please try again later.',
  AUTH_ACCOUNT_DISABLED: 'This account has been disabled.',
  AUTH_RATE_LIMITED: 'Too many attempts. Please wait a minute and try again.',
  AUTH_EMAIL_TAKEN: 'An account with this email already exists. Try signing in instead.',
  AUTH_REGISTRATION_DISABLED: 'Sign-up is currently disabled.',
  AUTH_OIDC_EMAIL_UNVERIFIED: 'Your Google account email is not verified. Verify it with Google and try again.',
  AUTH_OIDC_REDIRECT_NOT_ALLOWED: 'Google sign-in is not configured for this site.',
  AUTH_OIDC_LOGIN_FAILED: 'Google sign-in did not complete. Please try again.',
  CART_EMPTY: 'Your cart is empty.',
  PRODUCT_UNAVAILABLE: 'One or more items in your cart are no longer available. Please review your cart.',
  CURRENCY_MISMATCH: 'Items in your cart use different currencies and cannot be ordered together.',
  CART_LIMIT: 'Your cart is full. Remove an item before adding another.',
  WISHLIST_LIMIT: 'Your wishlist is full. Remove an item before saving another.',
  ADDRESS_NOT_FOUND: 'That address no longer exists. Please pick another one.',
  ADDRESS_LIMIT: 'You have reached the maximum number of saved addresses.',
  REVIEW_NOT_ELIGIBLE: 'Only customers who bought this item can review it.',
  REVIEW_ALREADY_EXISTS: 'You have already reviewed this product. Edit your existing review instead.',
  CANNOT_VOTE_OWN_REVIEW: 'You cannot vote on your own review.',
  AUTH_RESET_TOKEN_INVALID: 'This password reset link is invalid or has expired. Request a new one.',
  AUTH_VERIFY_TOKEN_INVALID: 'This verification link is invalid or has expired. Request a new one from your account.',
  AUTH_OTP_INVALID: 'That code is incorrect or has expired. Request a new code and try again.',
  OUT_OF_STOCK: 'Some items are out of stock or not available in the quantity you asked for.',
  INVALID_STATUS_TRANSITION: 'That order cannot be moved to this status.',
  ORDER_NOT_CANCELLABLE: 'This order can no longer be cancelled because it is already on its way.',
  PAYMENT_FAILED: 'Your payment did not go through. Please try again or use another payment method.',
  PAYMENT_NOT_FOUND: 'We could not find a payment for this order.',
  PAYMENT_METHOD_UNAVAILABLE: 'That payment method is not available for this order. Please choose another.',
  COUPON_INVALID: 'That coupon code is not valid.',
  COUPON_EXPIRED: 'That coupon has expired.',
  COUPON_NOT_APPLICABLE: 'That coupon does not apply to this order.',
  COUPON_USAGE_LIMIT: 'That coupon has reached its usage limit.',
  RETURN_NOT_ALLOWED: 'These items cannot be returned (already returned or not eligible).',
  RETURN_WINDOW_EXPIRED: 'The return window for this order has closed.',
  INVALID_PINCODE: 'Enter a valid 6-digit pincode.',
  DEPENDENCY_UNAVAILABLE: 'A service is temporarily unavailable. Please try again in a moment.',
}

/** Friendly text for a known error code/status, else the problem detail, else a generic fallback. */
export function errorMessage(error: unknown): string {
  const code = problemCode(error)
  if (code && FRIENDLY[code]) return FRIENDLY[code]
  if (isApiError(error)) {
    if (error.status === 429) return FRIENDLY['AUTH_RATE_LIMITED'] ?? 'Too many requests.'
    return error.problem.detail || error.problem.title
  }
  return 'Could not reach the server. Check your connection and try again.'
}

/** Friendly text for an error code passed back in a redirect (e.g. Google sign-in `?error=`). */
export function codeMessage(code: string): string {
  return FRIENDLY[code] ?? FRIENDLY['AUTH_OIDC_LOGIN_FAILED'] ?? 'Sign-in failed.'
}
