import { afterEach, describe, expect, it, vi } from 'vitest';

/** TD-SEC-02: Google sign-in must start at auth-service locally, at the edge when deployed. */
async function loadUrl(): Promise<string> {
  vi.resetModules();
  const { googleAuthorizationUrl } = await import('@/lib/http');
  return googleAuthorizationUrl();
}

describe('googleAuthorizationUrl', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('starts at auth-service (:8081) in development, not the gateway', async () => {
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_AUTH_BASE_URL', undefined as unknown as string);
    expect(await loadUrl()).toBe('http://localhost:8081/oauth2/authorization/google');
  });

  it('defaults to the API origin in a production build, never localhost:8081', async () => {
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_AUTH_BASE_URL', undefined as unknown as string);
    vi.stubEnv('VITE_API_BASE_URL', 'https://admin.example.com/');
    expect(await loadUrl()).toBe('https://admin.example.com/oauth2/authorization/google');
  });

  it('honours VITE_AUTH_BASE_URL when set', async () => {
    vi.stubEnv('VITE_AUTH_BASE_URL', 'https://auth.example.com/');
    expect(await loadUrl()).toBe('https://auth.example.com/oauth2/authorization/google');
  });
});
