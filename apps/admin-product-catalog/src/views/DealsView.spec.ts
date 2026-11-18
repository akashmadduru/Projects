import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import DealsView from '@/views/DealsView.vue';
import { useAuthStore } from '@/stores/auth';
import { installFetch, jsonResponse, resetModuleState } from '@/test/support';

/** TD-P7-19: deals list pages, the picker searches, names come from the batch endpoint. */
const productId = (i: number) => `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`;
const DEALS = Array.from({ length: 25 }, (_, i) => ({
  id: `d${i}`,
  productId: productId(i),
  variantId: null,
  title: null,
  dealPrice: 10,
  currency: 'USD',
  startsAt: '2026-01-01T00:00:00Z',
  endsAt: '2027-01-01T00:00:00Z',
  quantityLimit: 5,
  claimed: 0,
  active: true,
}));

function paged(content: unknown[]) {
  return { content, page: { size: 20, number: 0, totalElements: content.length, totalPages: 1 } };
}

function mountView() {
  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore().accessToken = 'live-access-token';
  return mount(DealsView, { global: { plugins: [pinia] } });
}

function arrange() {
  return installFetch(async (request) => {
    const url = new URL(request.url);
    if (url.pathname === '/api/admin/deals') return jsonResponse(200, DEALS);
    if (url.pathname === '/api/products/batch') {
      const ids = (url.searchParams.get('ids') ?? '').split(',');
      return jsonResponse(200, ids.map((id) => ({ id, name: `Product ${id.slice(-2)}` })));
    }
    if (url.pathname === '/api/products') {
      return jsonResponse(200, paged([{ id: productId(99), name: `Found ${url.searchParams.get('q') ?? 'all'}` }]));
    }
    return jsonResponse(404, {});
  });
}

describe('DealsView', () => {
  beforeEach(() => resetModuleState());
  afterEach(() => resetModuleState());

  it('pages the deal list 20 at a time and names products by id', async () => {
    const recorder = arrange();
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.findAll('[data-test="deal-row"]')).toHaveLength(20);
    expect(wrapper.text()).toContain('Product 00');
    expect(recorder.callsTo('/api/products/batch')).toHaveLength(1);

    await wrapper.get('[data-test="deal-next"]').trigger('click');
    expect(wrapper.findAll('[data-test="deal-row"]')).toHaveLength(5);
  });

  it('the product picker searches the catalog instead of loading a fixed first page', async () => {
    const recorder = arrange();
    const wrapper = mountView();
    await flushPromises();
    await wrapper.get('[data-test="deal-new"]').trigger('click');

    await wrapper.get('[data-test="deal-product-search"]').setValue('lamp');
    await new Promise((r) => setTimeout(r, 300));
    await flushPromises();

    const searches = recorder.callsTo('/api/products?').map((c) => new URL(c.url).searchParams);
    expect(searches.some((p) => p.get('q') === 'lamp' && p.get('size') === '20')).toBe(true);
    expect(wrapper.get('[data-test="deal-product"]').text()).toContain('Found lamp');
  });
});
