import type { ProblemDocument } from '@/types/api';

/**
 * The single error type the whole app deals with.
 *
 * Every failure — HTTP error, transport failure, unparseable body — arrives here,
 * so no view ever has to branch on `unknown` or stringify a response body itself.
 */
export class ApiError extends Error {
  /** HTTP status, or 0 when the request never got a response (network/CORS). */
  readonly status: number;
  /** RFC 9457 `code` member when the server sent one. */
  readonly code: string | null;
  /** The parsed problem document, when the body actually was problem+json. */
  readonly problem: ProblemDocument | null;
  /** Correlation id — the one thing that ties a UI failure to a server log line. */
  readonly correlationId: string | null;

  constructor(
    message: string,
    options: {
      status: number;
      code?: string | null;
      problem?: ProblemDocument | null;
      correlationId?: string | null;
      cause?: unknown;
    },
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'ApiError';
    this.status = options.status;
    this.code = options.code ?? null;
    this.problem = options.problem ?? null;
    this.correlationId = options.correlationId ?? null;
  }

  /** True for the codes that mean "this session is over, go to /login". */
  get isSessionLost(): boolean {
    return (
      this.status === 401 &&
      (this.code === 'AUTH_REFRESH_INVALID' ||
        this.code === 'AUTH_REFRESH_REUSE_DETECTED' ||
        this.code === 'UNAUTHENTICATED' ||
        this.code === 'AUTH_TOKEN_INVALID')
    );
  }

  /**
   * A concurrent refresh already rotated the cookie. NOT a hard logout: the
   * backend deliberately keeps the token family alive inside its 10s grace window.
   */
  get isRefreshRace(): boolean {
    return this.code === 'AUTH_REFRESH_RACE';
  }
}

/**
 * Friendly copy for codes whose server-side `detail` is intentionally terse or
 * whose remedy is a client-side action.
 *
 * This is a FALLBACK layer, not an override: the backend's `detail` wins whenever
 * it exists, because it is the only text that can describe *this* occurrence
 * (which field, which resource, which limit).
 */
const FRIENDLY_BY_CODE: Readonly<Record<string, string>> = Object.freeze({
  AUTH_INVALID_CREDENTIALS: 'That email and password combination is not valid.',
  AUTH_ACCOUNT_LOCKED: 'This account is temporarily locked after too many failed attempts.',
  AUTH_ACCOUNT_DISABLED: 'This account has been disabled. Contact an administrator.',
  AUTH_REGISTRATION_DISABLED: 'Self-service registration is switched off for this deployment.',
  AUTH_EMAIL_TAKEN: 'An account already exists for that email address.',
  AUTH_RATE_LIMITED: 'Too many requests. Wait a moment and try again.',
  AUTH_REFRESH_INVALID: 'Your session has expired. Please sign in again.',
  AUTH_REFRESH_REUSE_DETECTED: 'Your session was ended for security reasons. Please sign in again.',
  AUTH_REFRESH_RACE: 'Another tab refreshed this session. Please retry.',
  UNAUTHENTICATED: 'You are not signed in.',
  FORBIDDEN: 'You do not have permission to do that.',
  UPSTREAM_UNAVAILABLE: 'A downstream service is unavailable. Please try again shortly.',
  RESOURCE_NOT_FOUND: 'That item no longer exists.',
  RESOURCE_CONFLICT: 'This product was changed by someone else. Reload and try again.',
});

/** Message shown only when the server gave us nothing usable at all. */
const LAST_RESORT = 'The request failed and the server did not say why.';

function isNonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

/** Narrows an arbitrary parsed body to a problem document. */
export function asProblemDocument(body: unknown): ProblemDocument | null {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    return null;
  }
  const candidate = body as Record<string, unknown>;
  const looksLikeProblem =
    isNonBlank(candidate['code']) ||
    isNonBlank(candidate['type']) ||
    isNonBlank(candidate['title']) ||
    isNonBlank(candidate['detail']);
  return looksLikeProblem ? (candidate as ProblemDocument) : null;
}

/**
 * Picks the text a human should read.
 *
 * Order is deliberate: the server's `detail` describes this exact occurrence and
 * is never discarded in favour of generic copy. Only when it is missing do we
 * fall back to code-keyed copy, then the problem `title`, then a last resort.
 */
export function messageFor(problem: ProblemDocument | null, status: number): string {
  if (problem) {
    if (isNonBlank(problem.detail)) {
      return problem.detail;
    }
    const friendly = isNonBlank(problem.code) ? FRIENDLY_BY_CODE[problem.code] : undefined;
    if (isNonBlank(friendly)) {
      return friendly;
    }
    if (isNonBlank(problem.title)) {
      return problem.title;
    }
  }
  if (status === 0) {
    return 'Could not reach the server. Check that the API gateway is running.';
  }
  return `${LAST_RESORT} (HTTP ${status})`;
}

/** Builds an ApiError from a parsed (or unparseable) response body. */
export function apiErrorFrom(status: number, body: unknown, cause?: unknown): ApiError {
  const problem = asProblemDocument(body);
  return new ApiError(messageFor(problem, status), {
    status,
    code: problem?.code ?? null,
    problem,
    correlationId: problem?.correlationId ?? null,
    cause,
  });
}
