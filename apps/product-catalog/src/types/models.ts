/**
 * Domain models and error types
 */

import type { ApiProblem } from './api'

export interface ApiError extends Error {
  status: number
  problem: ApiProblem
  correlationId?: string
}

export type AsyncState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: T }
  | { status: 'error'; error: ApiError }
