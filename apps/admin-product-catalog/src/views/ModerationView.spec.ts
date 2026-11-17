import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import ModerationView from '@/views/ModerationView.vue';
import { useAuthStore } from '@/stores/auth';
import { installFetch, jsonResponse, resetModuleState } from '@/test/support';

const ITEM = {
  id: '66666666-6666-4666-8666-666666666666',
  sku: 'SELLER-1',
  name: 'Replica watch',
  sellerName: 'Acme Traders',
  sellerId: '77777777-7777-4777-8777-777777777777',
  price: 499,
  currency: 'INR',
  thumbnailUrl: null,
  createdAt: '2026-11-17T10:00:00Z',
  status: 'PENDING_REVIEW',
  note: null,
  flags: ['NO_IMAGE', 'BANNED_TERM'],
};

function paged(content: unknown[]) {
  return { content, page: { size: 20, number: 0, totalElements: content.length, totalPages: 1 } };
}

function mountView() {
  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore().accessToken = 'live-access-token';
  return mount(ModerationView, { global: { plugins: [pinia] } });
}

describe('ModerationView', () => {
  beforeEach(() => resetModuleState());
  afterEach(() => resetModuleState());

  it('lists pending products with their warnings', async () => {
    installFetch(async () => jsonResponse(200, paged([ITEM])));
    const wrapper = mountView();
    await flushPromises();

    const row = wrapper.find('[data-test="moderation-row"]');
    expect(row.text()).toContain('Replica watch');
    expect(row.text()).toContain('No image');
    expect(row.text()).toContain('Banned term');
  });

  it('approving posts the decision and reloads the queue', async () => {
    let approved = false;
    const fetch = installFetch(async (req) => {
      if (req.method === 'POST') {
        approved = true;
        return jsonResponse(200, { ...ITEM, status: 'APPROVED' });
      }
      return jsonResponse(200, paged(approved ? [] : [ITEM]));
    });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('button.btn-primary').trigger('click');
    await flushPromises();

    const post = fetch.calls.find((c) => c.method === 'POST');
    expect(post?.url).toContain(`/api/products/admin/moderation/${ITEM.id}`);
    expect(post?.body).toEqual({ decision: 'APPROVE', note: null });
    expect(wrapper.find('[data-test="moderation-empty"]').exists()).toBe(true);
  });

  it('a rejection cannot be sent without a note', async () => {
    const fetch = installFetch(async () => jsonResponse(200, paged([ITEM])));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('button.text-error').trigger('click');
    const reject = wrapper.find('.modal button.btn-error');
    expect(reject.attributes('disabled')).toBeDefined();

    await wrapper.find('#reject-note').setValue('Use real photos');
    await reject.trigger('click');
    await flushPromises();

    expect(fetch.calls.find((c) => c.method === 'POST')?.body).toEqual({
      decision: 'REJECT',
      note: 'Use real photos',
    });
  });
});
