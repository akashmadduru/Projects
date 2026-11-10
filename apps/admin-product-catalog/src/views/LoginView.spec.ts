import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';

import LoginView from '@/views/LoginView.vue';
import { useAuthStore } from '@/stores/auth';
import {
  installFetch,
  jsonResponse,
  problemResponse,
  resetModuleState,
  TEST_USER,
  tokenResponse,
  type FetchRecorder,
} from '@/test/support';

const STUB = { template: '<div />' };

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: STUB },
      { path: '/register', name: 'register', component: STUB },
      { path: '/dashboard', name: 'dashboard', component: STUB },
      { path: '/products', name: 'products', component: STUB },
    ],
  });
}

async function mountLogin(initialPath = '/login') {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createTestRouter();
  await router.push(initialPath);
  await router.isReady();
  const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });
  return { wrapper, router };
}

describe('LoginView', () => {
  beforeEach(() => {
    resetModuleState();
  });

  afterEach(() => {
    resetModuleState();
  });

  function arrangeSuccessfulLogin(): FetchRecorder {
    return installFetch(async (request) => {
      if (request.url.includes('/api/auth/login')) {
        return jsonResponse(200, tokenResponse('login-access-token'));
      }
      if (request.url.includes('/api/auth/me')) {
        return jsonResponse(200, TEST_USER);
      }
      return problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call');
    });
  }

  it('signs in and lands on the dashboard', async () => {
    const recorder = arrangeSuccessfulLogin();
    const { wrapper, router } = await mountLogin();

    await wrapper.get('[data-test="email"]').setValue(TEST_USER.email);
    await wrapper.get('[data-test="password"]').setValue('a-correct-horse-battery');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    const loginCall = recorder.callsTo('/api/auth/login')[0];
    expect(loginCall?.method).toBe('POST');
    expect(loginCall?.body).toEqual({
      email: TEST_USER.email,
      password: 'a-correct-horse-battery',
    });
    expect(router.currentRoute.value.path).toBe('/dashboard');
    expect(useAuthStore().accessToken).toBe('login-access-token');
  });

  it('returns the user to where the guard interrupted them', async () => {
    arrangeSuccessfulLogin();
    const { wrapper, router } = await mountLogin('/login?redirect=/products%3Fpage%3D2');

    await wrapper.get('[data-test="email"]').setValue(TEST_USER.email);
    await wrapper.get('[data-test="password"]').setValue('a-correct-horse-battery');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(router.currentRoute.value.fullPath).toBe('/products?page=2');
  });

  it("shows the backend's message on AUTH_INVALID_CREDENTIALS and stays put", async () => {
    installFetch(async () =>
      problemResponse(401, 'AUTH_INVALID_CREDENTIALS', 'Invalid email or password.'),
    );
    const { wrapper, router } = await mountLogin();

    await wrapper.get('[data-test="email"]').setValue(TEST_USER.email);
    await wrapper.get('[data-test="password"]').setValue('wrong-password-here');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    const alert = wrapper.get('[data-test="problem-alert"]');
    expect(alert.text()).toContain('Invalid email or password.');
    // The correlation id is what ties this screen to a server log line.
    expect(alert.text()).toContain('11111111-2222-4333-8444-555555555555');
    expect(router.currentRoute.value.path).toBe('/login');
    expect(useAuthStore().isAuthenticated).toBe(false);
  });

  it('ignores an unsafe redirect target', async () => {
    arrangeSuccessfulLogin();
    // An absolute off-site URL in ?redirect= is an open-redirect attempt.
    const { wrapper, router } = await mountLogin('/login?redirect=https://evil.example.com');

    await wrapper.get('[data-test="email"]').setValue(TEST_USER.email);
    await wrapper.get('[data-test="password"]').setValue('a-correct-horse-battery');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(router.currentRoute.value.path).toBe('/dashboard');
  });

  it('validates client-side before touching the network', async () => {
    const recorder = installFetch(async () => jsonResponse(200, {}));
    const { wrapper } = await mountLogin();

    await wrapper.get('[data-test="email"]').setValue('not-an-email');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(wrapper.get('[data-test="email-error"]').text()).toContain('valid email');
    expect(wrapper.get('[data-test="password-error"]').text()).toContain('required');
    expect(recorder.calls).toHaveLength(0);
  });

  it('links the Google button to the gateway, not to an XHR endpoint', async () => {
    installFetch(async () => jsonResponse(200, {}));
    const { wrapper } = await mountLogin();

    // A fetch to the OAuth2 endpoint could never work: it redirects cross-origin
    // to Google. The button must be a top-level navigation.
    expect(wrapper.get('[data-test="google"]').attributes('type')).toBe('button');
  });

  it('acknowledges a completed registration', async () => {
    installFetch(async () => jsonResponse(200, {}));
    const { wrapper } = await mountLogin('/login?registered=new.user%40acme.test');

    expect(wrapper.get('[data-test="registered-notice"]').text()).toContain('new.user@acme.test');
  });
});
