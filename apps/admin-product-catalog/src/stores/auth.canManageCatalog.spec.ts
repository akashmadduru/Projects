import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { useAuthStore } from '@/stores/auth';
import { resetModuleState, TEST_USER } from '@/test/support';

describe('auth store canManageCatalog', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    resetModuleState();
  });

  afterEach(() => {
    resetModuleState();
  });

  it('is false when there is no signed-in user', () => {
    const auth = useAuthStore();
    expect(auth.canManageCatalog).toBe(false);
  });

  it('is false for a user without CATALOG_MANAGER or ADMIN', () => {
    const auth = useAuthStore();
    auth.user = { ...TEST_USER, roles: ['VIEWER'] };
    expect(auth.canManageCatalog).toBe(false);
  });

  it('is true for CATALOG_MANAGER', () => {
    const auth = useAuthStore();
    auth.user = { ...TEST_USER, roles: ['CATALOG_MANAGER'] };
    expect(auth.canManageCatalog).toBe(true);
  });

  it('is true for ADMIN', () => {
    const auth = useAuthStore();
    auth.user = { ...TEST_USER, roles: ['ADMIN'] };
    expect(auth.canManageCatalog).toBe(true);
  });
});
