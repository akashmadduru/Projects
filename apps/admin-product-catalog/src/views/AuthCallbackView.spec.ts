import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';

import AuthCallbackView from '@/views/AuthCallbackView.vue';
import { rememberPostLoginRedirect } from '@/lib/oauthRedirect';
import { useAuthStore } from '@/stores/auth';
import {
  installFetch,
  jsonResponse,
  problemResponse,
  resetModuleState,
  TEST_USER,
  tokenResponse,
} from '@/test/support';

const STUB = { template: '<div />' };

async function mountCallback(): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: STUB },
      { path: '/dashboard', name: 'dashboard', component: STUB },
      { path: '/products', name: 'products', component: STUB },
      { path: '/auth/callback', name: 'auth-callback', component: STUB },
    ],
  });
  await router.push('/auth/callback');
  await router.isReady();
  const wrapper = mount(AuthCallbackView, { global: { plugins: [pinia, router] } });
  return { wrapper, router };
}

describe('AuthCallbackView', () => {
  beforeEach(() => {
    resetModuleState();
    sessionStorage.clear();
  });

  afterEach(() => {
    resetModuleState();
    sessionStorage.clear();
  });

  it('exchanges the refresh cookie for the first access token and enters the app', async () => {
    // auth-service redirects here with NOTHING in the URL — no token, no code.
    // The cookie is the whole handoff.
    const recorder = installFetch(async (request) => {
      if (request.url.includes('/api/auth/refresh')) {
        return jsonResponse(200, tokenResponse('google-session-token'));
      }
      if (request.url.includes('/api/auth/me')) {
        return jsonResponse(200, TEST_USER);
      }
      return problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call');
    });

    const { router } = await mountCallback();
    await flushPromises();

    const refreshCall = recorder.callsTo('/api/auth/refresh')[0];
    expect(refreshCall?.method).toBe('POST');
    expect(refreshCall?.credentials).toBe('include');
    // No bearer: this call is authenticated by the cookie alone.
    expect(refreshCall?.authorization).toBeNull();
    expect(useAuthStore().accessToken).toBe('google-session-token');
    expect(router.currentRoute.value.path).toBe('/dashboard');
  });

  it('returns the user to the page they originally asked for', async () => {
    installFetch(async (request) =>
      request.url.includes('/api/auth/refresh')
        ? jsonResponse(200, tokenResponse('google-session-token'))
        : jsonResponse(200, TEST_USER),
    );
    rememberPostLoginRedirect('/products?page=3');

    const { router } = await mountCallback();
    await flushPromises();

    expect(router.currentRoute.value.fullPath).toBe('/products?page=3');
  });

  it('shows an error state when the federated login did not actually happen', async () => {
    // What this environment produces today: Google credentials are not
    // configured, so no refresh cookie was ever set.
    installFetch(async () =>
      problemResponse(401, 'AUTH_REFRESH_INVALID', 'No refresh token was presented.'),
    );

    const { wrapper, router } = await mountCallback();
    await flushPromises();

    expect(wrapper.get('[data-test="problem-alert"]').text()).toContain(
      'No refresh token was presented.',
    );
    expect(router.currentRoute.value.path).toBe('/auth/callback');
  });
});
