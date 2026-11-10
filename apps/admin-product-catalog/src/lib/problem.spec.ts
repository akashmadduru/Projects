import { describe, expect, it } from 'vitest';

import { ApiError, apiErrorFrom, messageFor } from '@/lib/problem';

/**
 * One error-parsing path for the whole platform. The rule under test: when the
 * API took the trouble to say what went wrong, the user sees that — never a
 * generic "something went wrong", and never raw JSON.
 */
describe('problem document handling', () => {
  it("prefers the server's detail over any local copy", () => {
    const message = messageFor(
      {
        code: 'AUTH_INVALID_CREDENTIALS',
        title: 'Invalid credentials',
        detail: 'Invalid email or password.',
        status: 401,
      },
      401,
    );
    expect(message).toBe('Invalid email or password.');
  });

  it('falls back to code-keyed copy when detail is missing', () => {
    expect(messageFor({ code: 'AUTH_EMAIL_TAKEN', status: 409 }, 409)).toBe(
      'An account already exists for that email address.',
    );
  });

  it('falls back to the problem title when the code is unknown', () => {
    expect(messageFor({ code: 'SOMETHING_NEW', title: 'A brand new failure', status: 400 }, 400)).toBe(
      'A brand new failure',
    );
  });

  it('explains a transport failure rather than blaming the user', () => {
    expect(messageFor(null, 0)).toContain('Could not reach the server');
  });

  it('never leaks a non-problem body into the message', () => {
    const error = apiErrorFrom(500, { stackTrace: 'com.acme...NullPointerException' });
    expect(error.message).not.toContain('NullPointerException');
    expect(error.problem).toBeNull();
    expect(error.status).toBe(500);
  });

  it('carries the correlation id and classifies session-ending codes', () => {
    const error = apiErrorFrom(401, {
      code: 'AUTH_REFRESH_REUSE_DETECTED',
      detail: 'Refresh token reuse detected.',
      correlationId: 'abc-123',
    });

    expect(error).toBeInstanceOf(ApiError);
    expect(error.correlationId).toBe('abc-123');
    expect(error.isSessionLost).toBe(true);
    expect(error.isRefreshRace).toBe(false);
  });

  it('classifies AUTH_REFRESH_RACE as recoverable, not a logout', () => {
    const error = apiErrorFrom(401, { code: 'AUTH_REFRESH_RACE', detail: 'Already rotated.' });
    expect(error.isRefreshRace).toBe(true);
    expect(error.isSessionLost).toBe(false);
  });
});
