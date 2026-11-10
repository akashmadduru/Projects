/**
 * Error alert display component - clean minimalist design
 */

import type { ApiError } from '../types'

interface Props {
  error?: ApiError
  message?: string
  onDismiss?: () => void
}

export function ErrorAlert({ error, message, onDismiss }: Props) {
  const title = error?.problem.title || 'Error'
  const detail = error?.problem.detail || message || 'An unexpected error occurred'
  const correlationId = error?.correlationId

  return (
    <div className="rounded-lg p-4 mb-6 border border-outline/50 bg-surface-variant animate-slide-up">
      <div className="flex items-start justify-between gap-4">
        <div className="flex gap-3 flex-1">
          <div className="flex-shrink-0 mt-0.5">
            <svg
              className="w-5 h-5 text-on-surface"
              fill="currentColor"
              viewBox="0 0 20 20"
              aria-hidden="true"
            >
              <path
                fillRule="evenodd"
                d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                clipRule="evenodd"
              />
            </svg>
          </div>
          <div className="flex-1">
            <h3 className="font-semibold text-on-surface">{title}</h3>
            {detail && (
              <p className="text-sm text-on-surface mt-1">{detail}</p>
            )}
            {correlationId && (
              <p className="text-xs text-on-surface-muted mt-2 font-mono">
                ID: {correlationId}
              </p>
            )}
          </div>
        </div>
        {onDismiss && (
          <button
            onClick={onDismiss}
            className="flex-shrink-0 text-on-surface hover:text-on-surface/60 font-semibold text-xl transition-colors duration-200 hover:bg-outline/10 rounded-lg p-1"
            aria-label="Dismiss error"
          >
            ×
          </button>
        )}
      </div>
    </div>
  )
}
