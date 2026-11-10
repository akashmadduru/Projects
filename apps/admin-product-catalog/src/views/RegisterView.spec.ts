import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';

import RegisterView from '@/views/RegisterView.vue';
import {
  installFetch,
  jsonResponse,
  problemResponse,
  resetModuleState,
  TEST_USER,
} from '@/test/support';

const STUB = { template: '<div />' };

async function mountRegister(): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: STUB },
      { path: '/register', name: 'register', component: STUB },
    ],
  });
  await router.push('/register');
  await router.isReady();
  const wrapper = mount(RegisterView, { global: { plugins: [pinia, router] } });
  return { wrapper, router };
}

async function fillValidForm(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.get('[data-test="display-name"]').setValue('New User');
  await wrapper.get('[data-test="email"]').setValue('new.user@acme.test');
  await wrapper.get('[data-test="password"]').setValue('a-long-enough-password');
}

describe('RegisterView', () => {
  beforeEach(() => {
    resetModuleState();
  });

  afterEach(() => {
    resetModuleState();
  });

  it('creates the account and sends the user to sign in', async () => {
    const recorder = installFetch(async () =>
      jsonResponse(201, { ...TEST_USER, email: 'new.user@acme.test', roles: ['VIEWER'] }),
    );
    const { wrapper, router } = await mountRegister();

    await fillValidForm(wrapper);
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(recorder.callsTo('/api/auth/register')[0]?.body).toEqual({
      email: 'new.user@acme.test',
      password: 'a-long-enough-password',
      displayName: 'New User',
    });
    expect(router.currentRoute.value.path).toBe('/login');
    expect(router.currentRoute.value.query['registered']).toBe('new.user@acme.test');
  });

  it('surfaces AUTH_REGISTRATION_DISABLED verbatim rather than hiding the feature', async () => {
    // Registration is off by default in real deployments. The UI does not guess
    // at the flag; it shows whatever the deployment actually answers.
    installFetch(async () =>
      problemResponse(403, 'AUTH_REGISTRATION_DISABLED', 'Self-service registration is disabled.'),
    );
    const { wrapper, router } = await mountRegister();

    await fillValidForm(wrapper);
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(wrapper.get('[data-test="problem-alert"]').text()).toContain(
      'Self-service registration is disabled.',
    );
    expect(router.currentRoute.value.path).toBe('/register');
  });

  it('surfaces AUTH_EMAIL_TAKEN', async () => {
    installFetch(async () =>
      problemResponse(409, 'AUTH_EMAIL_TAKEN', 'That email address is already registered.'),
    );
    const { wrapper } = await mountRegister();

    await fillValidForm(wrapper);
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(wrapper.get('[data-test="problem-alert"]').text()).toContain('already registered');
  });

  it('enforces the server-side 12 character minimum before submitting', async () => {
    const recorder = installFetch(async () => jsonResponse(201, TEST_USER));
    const { wrapper } = await mountRegister();

    await wrapper.get('[data-test="display-name"]').setValue('New User');
    await wrapper.get('[data-test="email"]').setValue('new.user@acme.test');
    await wrapper.get('[data-test="password"]').setValue('short');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(wrapper.get('[data-test="password-error"]').text()).toContain('at least 12');
    expect(recorder.calls).toHaveLength(0);
  });
});
