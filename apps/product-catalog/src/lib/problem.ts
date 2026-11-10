/**
 * RFC 9457 Problem Details error handling
 */

import type { ApiProblem, ApiError } from '../types'

export class ApiErrorImpl extends Error implements ApiError {
  status: number
  problem: ApiProblem
  correlationId?: string

  constructor(status: number, problem: ApiProblem, correlationId?: string) {
    super(problem.detail || problem.title)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
    this.correlationId = correlationId
    Object.setPrototypeOf(this, ApiErrorImpl.prototype)
  }
}

export function apiErrorFrom(status: number, body: unknown): ApiError {
  const correlationId = (body as Record<string, unknown>)?.['correlationId'] as string | undefined

  if (typeof body === 'object' && body !== null) {
    const problem = body as Partial<ApiProblem>
    if (problem.type && problem.title) {
      return new ApiErrorImpl(status, problem as ApiProblem, correlationId)
    }
  }

  // Fallback to HTTP status message
  const problem: ApiProblem = {
    type: `about:blank`,
    title: getHttpStatusMessage(status),
    status,
    detail: typeof body === 'string' ? body : undefined,
  }

  return new ApiErrorImpl(status, problem, correlationId)
}

function getHttpStatusMessage(status: number): string {
  const messages: Record<number, string> = {
    400: 'Bad Request',
    401: 'Unauthorized',
    403: 'Forbidden',
    404: 'Not Found',
    409: 'Conflict',
    422: 'Unprocessable Entity',
    429: 'Too Many Requests',
    500: 'Internal Server Error',
    502: 'Bad Gateway',
    503: 'Service Unavailable',
  }
  return messages[status] || `HTTP ${status}`
}
