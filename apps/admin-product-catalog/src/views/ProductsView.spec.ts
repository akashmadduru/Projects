import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';

import ProductsView from '@/views/ProductsView.vue';
import { useAuthStore } from '@/stores/auth';
import {
  installFetch,
  jsonResponse,
  problemResponse,
  resetModuleState,
  TEST_USER,
  type FetchRecorder,
} from '@/test/support';

const PRODUCTS = [
  {
    id: '11111111-1111-4111-8111-111111111111',
    sku: 'ACME-001',
    name: 'Widget',
    priceAmount: 19.99,
    priceCurrency: 'USD',
    active: true,
    version: 0,
    categoryId: null,
    sellerName: null,
    specifications: null,
    images: [],
    lastKnownQuantity: null,
  },
  {
    id: '22222222-2222-4222-8222-222222222222',
    sku: 'ACME-002',
    name: 'Discontinued Gadget',
    priceAmount: 5.5,
    priceCurrency: 'USD',
    active: false,
    version: 3,
    categoryId: null,
    sellerName: null,
    specifications: null,
    images: [],
    lastKnownQuantity: null,
  },
];

function pagedModel(
  content: unknown[],
  page: { size: number; number: number; totalElements: number; totalPages: number },
): Record<string, unknown> {
  return { content, page };
}

const STUB = { template: '<div />' };

async function mountProducts(roles: readonly string[] = []) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore();
  auth.accessToken = 'live-access-token';
  if (roles.length > 0) {
    auth.user = { ...TEST_USER, roles };
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/products', name: 'products', component: STUB },
      { path: '/products/new', name: 'new-product', component: STUB },
    ],
  });
  await router.push('/products');
  await router.isReady();
  return mount(ProductsView, { global: { plugins: [pinia, router] } });
}

describe('ProductsView', () => {
  beforeEach(() => {
    resetModuleState();
  });

  afterEach(() => {
    resetModuleState();
  });

  it('renders the loading state before the first response arrives', async () => {
    installFetch(
      () =>
        new Promise<Response>(() => {
          /* never resolves: the request is still in flight */
        }),
    );

    const wrapper = await mountProducts();
    await flushPromises();

    expect(wrapper.find('[data-test="state-loading"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="state-success"]').exists()).toBe(false);
  });

  it('renders the catalog on success', async () => {
    installFetch(async () =>
      jsonResponse(200, pagedModel(PRODUCTS, { size: 20, number: 0, totalElements: 2, totalPages: 1 })),
    );

    const wrapper = await mountProducts();
    await flushPromises();

    const rows = wrapper.findAll('[data-test="product-row"]');
    expect(rows).toHaveLength(2);
    expect(rows[0]?.text()).toContain('ACME-001');
    expect(rows[0]?.text()).toContain('Widget');
    // Inactive products are returned by Phase 1E and must be visible as such.
    expect(rows[1]?.text()).toContain('Inactive');
    expect(wrapper.find('[data-test="state-loading"]').exists()).toBe(false);
  });

  it('renders the empty state when the catalog has no rows', async () => {
    installFetch(async () =>
      jsonResponse(200, pagedModel([], { size: 20, number: 0, totalElements: 0, totalPages: 0 })),
    );

    const wrapper = await mountProducts();
    await flushPromises();

    expect(wrapper.find('[data-test="state-empty"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="product-row"]').exists()).toBe(false);
  });

  it("renders the backend's problem detail on failure", async () => {
    installFetch(async () =>
      problemResponse(503, 'UPSTREAM_UNAVAILABLE', 'product-service is unavailable.'),
    );

    const wrapper = await mountProducts();
    await flushPromises();

    expect(wrapper.find('[data-test="state-error"]').exists()).toBe(true);
    expect(wrapper.get('[data-test="problem-alert"]').text()).toContain(
      'product-service is unavailable.',
    );
  });

  it('recovers when the retry succeeds', async () => {
    let attempt = 0;
    installFetch(async () => {
      attempt += 1;
      return attempt === 1
        ? problemResponse(503, 'UPSTREAM_UNAVAILABLE', 'product-service is unavailable.')
        : jsonResponse(
            200,
            pagedModel(PRODUCTS, { size: 20, number: 0, totalElements: 2, totalPages: 1 }),
          );
    });

    const wrapper = await mountProducts();
    await flushPromises();
    expect(wrapper.find('[data-test="state-error"]').exists()).toBe(true);

    await wrapper.get('[data-test="retry"]').trigger('click');
    await flushPromises();

    expect(wrapper.findAll('[data-test="product-row"]')).toHaveLength(2);
  });

  it('requests the right page and size as the user pages through', async () => {
    const recorder: FetchRecorder = installFetch(async (request) => {
      const pageNumber = Number(new URL(request.url).searchParams.get('page') ?? '0');
      return jsonResponse(
        200,
        pagedModel(PRODUCTS, { size: 20, number: pageNumber, totalElements: 45, totalPages: 3 }),
      );
    });

    const wrapper = await mountProducts();
    await flushPromises();

    expect(recorder.calls[0]?.url).toContain('page=0');
    expect(recorder.calls[0]?.url).toContain('size=20');
    expect(wrapper.get('[data-test="page-indicator"]').text()).toContain('Page 1 of 3');
    // Nothing before page 0.
    expect(wrapper.get('[data-test="previous-page"]').attributes('disabled')).toBeDefined();

    await wrapper.get('[data-test="next-page"]').trigger('click');
    await flushPromises();

    expect(recorder.calls[1]?.url).toContain('page=1');
    expect(wrapper.get('[data-test="page-indicator"]').text()).toContain('Page 2 of 3');

    await wrapper.get('[data-test="previous-page"]').trigger('click');
    await flushPromises();

    expect(recorder.calls[2]?.url).toContain('page=0');
    expect(recorder.calls).toHaveLength(3);
  });

  it('does not page past the last page', async () => {
    const recorder = installFetch(async () =>
      jsonResponse(200, pagedModel(PRODUCTS, { size: 20, number: 0, totalElements: 2, totalPages: 1 })),
    );

    const wrapper = await mountProducts();
    await flushPromises();

    expect(wrapper.get('[data-test="next-page"]').attributes('disabled')).toBeDefined();
    await wrapper.get('[data-test="next-page"]').trigger('click');
    await flushPromises();

    expect(recorder.calls).toHaveLength(1);
  });

  it('sends the bearer token with the catalog request', async () => {
    const recorder = installFetch(async () =>
      jsonResponse(200, pagedModel([], { size: 20, number: 0, totalElements: 0, totalPages: 0 })),
    );

    await mountProducts();
    await flushPromises();

    expect(recorder.calls[0]?.authorization).toBe('Bearer live-access-token');
  });

  describe('role-gated write actions', () => {
    function arrangeCatalog() {
      return installFetch(async () =>
        jsonResponse(200, pagedModel(PRODUCTS, { size: 20, number: 0, totalElements: 2, totalPages: 1 })),
      );
    }

    it('hides New Product and row actions for a viewer without CATALOG_MANAGER/ADMIN', async () => {
      arrangeCatalog();
      const wrapper = await mountProducts(['VIEWER']);
      await flushPromises();

      expect(wrapper.find('[data-test="new-product"]').exists()).toBe(false);
      expect(wrapper.find('[data-test="edit-product"]').exists()).toBe(false);
      expect(wrapper.find('[data-test="delete-product"]').exists()).toBe(false);
    });

    it('shows New Product and row actions for CATALOG_MANAGER', async () => {
      arrangeCatalog();
      const wrapper = await mountProducts(['CATALOG_MANAGER']);
      await flushPromises();

      expect(wrapper.find('[data-test="new-product"]').exists()).toBe(true);
      expect(wrapper.findAll('[data-test="edit-product"]')).toHaveLength(2);
      expect(wrapper.findAll('[data-test="delete-product"]')).toHaveLength(2);
    });

    it('shows New Product and row actions for ADMIN', async () => {
      arrangeCatalog();
      const wrapper = await mountProducts(['ADMIN']);
      await flushPromises();

      expect(wrapper.find('[data-test="new-product"]').exists()).toBe(true);
    });
  });

  describe('create/edit/delete flows', () => {
    function arrangeCatalog() {
      return installFetch(async () =>
        jsonResponse(200, pagedModel(PRODUCTS, { size: 20, number: 0, totalElements: 2, totalPages: 1 })),
      );
    }

    it('links "New Product" to the dedicated create page rather than opening a modal', async () => {
      arrangeCatalog();
      const wrapper = await mountProducts(['CATALOG_MANAGER']);
      await flushPromises();

      const link = wrapper.get('[data-test="new-product"]');
      expect(link.attributes('href')).toBe('/products/new');
    });

    it('shows the conflict message on a 409 during edit', async () => {
      arrangeCatalog();
      installFetch(async (request) => {
        if (request.method === 'PUT') {
          return problemResponse(
            409,
            'RESOURCE_CONFLICT',
            'The product was modified by another request.',
          );
        }
        return jsonResponse(
          200,
          pagedModel(PRODUCTS, { size: 20, number: 0, totalElements: 2, totalPages: 1 }),
        );
      });

      const wrapper = await mountProducts(['CATALOG_MANAGER']);
      await flushPromises();

      await wrapper.get('[data-test="edit-product"]').trigger('click');
      await wrapper.get('[data-test="product-form-modal"] form').trigger('submit');
      await flushPromises();

      expect(wrapper.get('[data-test="product-form-error"]').text()).toContain(
        'The product was modified by another request.',
      );
      expect(wrapper.find('[data-test="product-form-reload"]').exists()).toBe(true);
    });

    it('deletes a product after confirmation and reloads the list', async () => {
      let listCalls = 0;
      const recorder = installFetch(async (request) => {
        if (request.method === 'GET' && request.url.includes('/api/products')) {
          listCalls += 1;
          return jsonResponse(
            200,
            pagedModel(PRODUCTS, { size: 20, number: 0, totalElements: 2, totalPages: 1 }),
          );
        }
        if (request.method === 'DELETE') {
          return jsonResponse(204);
        }
        return problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call');
      });

      const wrapper = await mountProducts(['CATALOG_MANAGER']);
      await flushPromises();

      await wrapper.get('[data-test="delete-product"]').trigger('click');
      expect(wrapper.find('[data-test="confirm-dialog"]').exists()).toBe(true);

      await wrapper.get('[data-test="confirm-dialog-confirm"]').trigger('click');
      await flushPromises();

      const deleteCall = recorder.calls.find((call) => call.method === 'DELETE');
      expect(deleteCall?.url).toContain(`/api/products/${PRODUCTS[0]?.id}`);
      expect(wrapper.find('[data-test="confirm-dialog"]').exists()).toBe(false);
      expect(listCalls).toBe(2);
    });

    it('cancelling the delete dialog issues no request', async () => {
      arrangeCatalog();
      const wrapper = await mountProducts(['CATALOG_MANAGER']);
      await flushPromises();

      await wrapper.get('[data-test="delete-product"]').trigger('click');
      await wrapper.get('[data-test="confirm-dialog-cancel"]').trigger('click');

      expect(wrapper.find('[data-test="confirm-dialog"]').exists()).toBe(false);
    });
  });
});
