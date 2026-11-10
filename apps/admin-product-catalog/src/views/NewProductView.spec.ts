import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';

import NewProductView from '@/views/NewProductView.vue';
import { useAuthStore } from '@/stores/auth';
import {
  installFetch,
  jsonResponse,
  problemResponse,
  resetModuleState,
  TEST_USER,
} from '@/test/support';

const STUB = { template: '<div />' };

function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/products', name: 'products', component: STUB },
      { path: '/products/new', name: 'new-product', component: STUB },
    ],
  });
}

async function mountNewProduct() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore();
  auth.accessToken = 'live-access-token';
  auth.user = { ...TEST_USER, roles: ['CATALOG_MANAGER'] };
  const router = createTestRouter();
  await router.push('/products/new');
  await router.isReady();
  const wrapper = mount(NewProductView, { global: { plugins: [pinia, router] } });
  return { wrapper, router };
}

describe('NewProductView', () => {
  beforeEach(() => {
    resetModuleState();
  });

  afterEach(() => {
    resetModuleState();
  });

  it('creates a product and navigates back to the catalog on success', async () => {
    const recorder = installFetch(async (request) => {
      if (request.method === 'POST' && request.url.includes('/api/products')) {
        return jsonResponse(201, {
          id: '33333333-3333-4333-8333-333333333333',
          sku: 'ACME-003',
          name: 'New Thing',
          priceAmount: 9.99,
          priceCurrency: 'USD',
          active: true,
          version: 0,
        });
      }
      return problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call');
    });

    const { wrapper, router } = await mountNewProduct();

    await wrapper.get('[data-test="new-product-sku"]').setValue('ACME-003');
    await wrapper.get('[data-test="new-product-name"]').setValue('New Thing');
    await wrapper.get('[data-test="new-product-price-amount"]').setValue('9.99');
    await wrapper.get('[data-test="new-product-price-currency"]').setValue('USD');
    await wrapper.get('[data-test="new-product-initial-quantity"]').setValue('5');
    await wrapper.get('[data-test="new-product-form"] form').trigger('submit');
    await flushPromises();

    const createCall = recorder.callsTo('/api/products').find((call) => call.method === 'POST');
    expect(createCall?.body).toEqual({
      sku: 'ACME-003',
      name: 'New Thing',
      priceAmount: 9.99,
      priceCurrency: 'USD',
      active: true,
      initialQuantity: 5,
    });
    expect(router.currentRoute.value.name).toBe('products');
  });

  it('shows validation errors and issues no request when required fields are blank', async () => {
    const recorder = installFetch(async () =>
      problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call'),
    );

    const { wrapper } = await mountNewProduct();

    await wrapper.get('[data-test="new-product-form"] form').trigger('submit');
    await flushPromises();

    expect(wrapper.get('[data-test="new-product-sku-error"]').text()).toContain('required');
    expect(wrapper.get('[data-test="new-product-name-error"]').text()).toContain('required');
    expect(recorder.calls).toHaveLength(0);
  });

  it('shows the server error and stays on the page on failure', async () => {
    installFetch(async () =>
      problemResponse(409, 'RESOURCE_CONFLICT', 'Product sku already exists: ACME-003'),
    );

    const { wrapper, router } = await mountNewProduct();

    await wrapper.get('[data-test="new-product-sku"]').setValue('ACME-003');
    await wrapper.get('[data-test="new-product-name"]').setValue('New Thing');
    await wrapper.get('[data-test="new-product-price-amount"]').setValue('9.99');
    await wrapper.get('[data-test="new-product-price-currency"]').setValue('USD');
    await wrapper.get('[data-test="new-product-initial-quantity"]').setValue('5');
    await wrapper.get('[data-test="new-product-form"] form').trigger('submit');
    await flushPromises();

    expect(wrapper.get('[data-test="new-product-error"]').text()).toContain(
      'Product sku already exists: ACME-003',
    );
    expect(router.currentRoute.value.name).toBe('new-product');
  });

  it('requires initial quantity only while active is checked, mirroring the backend @AssertTrue', async () => {
    const recorder = installFetch(async (request) => {
      if (request.method === 'POST' && request.url.includes('/api/products')) {
        return jsonResponse(201, {
          id: '44444444-4444-4444-8444-444444444444',
          sku: 'ACME-004',
          name: 'Inactive Thing',
          priceAmount: 1,
          priceCurrency: 'USD',
          active: false,
          version: 0,
        });
      }
      return problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call');
    });

    const { wrapper } = await mountNewProduct();

    await wrapper.get('[data-test="new-product-sku"]').setValue('ACME-004');
    await wrapper.get('[data-test="new-product-name"]').setValue('Inactive Thing');
    await wrapper.get('[data-test="new-product-price-amount"]').setValue('1');
    await wrapper.get('[data-test="new-product-price-currency"]').setValue('USD');
    await wrapper.get('[data-test="new-product-form"] form').trigger('submit');
    await flushPromises();

    // Still active (the default) and initial quantity is blank: blocked client-side.
    expect(wrapper.get('[data-test="new-product-initial-quantity-error"]').text()).toContain(
      'required',
    );
    expect(recorder.calls).toHaveLength(0);

    // Unchecking active removes the requirement entirely - the field itself disappears.
    await wrapper.get('[data-test="new-product-active"]').setValue(false);
    await wrapper.get('[data-test="new-product-form"] form').trigger('submit');
    await flushPromises();

    expect(wrapper.find('[data-test="new-product-initial-quantity"]').exists()).toBe(false);
    const createCall = recorder.callsTo('/api/products').find((call) => call.method === 'POST');
    expect(createCall?.body).toEqual({
      sku: 'ACME-004',
      name: 'Inactive Thing',
      priceAmount: 1,
      priceCurrency: 'USD',
      active: false,
    });
  });

  it('cancel navigates back to the catalog without submitting', async () => {
    const recorder = installFetch(async () =>
      problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call'),
    );

    const { wrapper, router } = await mountNewProduct();

    await wrapper.get('[data-test="new-product-cancel"]').trigger('click');
    await flushPromises();

    expect(recorder.calls).toHaveLength(0);
    expect(router.currentRoute.value.name).toBe('products');
  });
});
