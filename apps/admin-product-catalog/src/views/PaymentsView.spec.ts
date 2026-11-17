import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import PaymentsView from '@/views/PaymentsView.vue';
import { useAuthStore } from '@/stores/auth';
import { installFetch, jsonResponse, resetModuleState } from '@/test/support';

const RAZORPAY_ROW = {
  id: '33333333-3333-4333-8333-333333333333',
  orderId: '44444444-4444-4444-8444-444444444444',
  userId: '55555555-5555-4555-8555-555555555555',
  method: 'CARD',
  mode: 'RAZORPAY',
  amount: 1499,
  currency: 'INR',
  status: 'PARTIALLY_REFUNDED',
  refundedAmount: 500,
  createdAt: '2026-11-15T10:00:00Z',
  updatedAt: '2026-11-15T11:00:00Z',
  stripePaymentIntentId: null,
  providerOrderId: 'order_TEST123',
  providerPaymentId: 'pay_TEST456',
};

function paged(content: unknown[]): Record<string, unknown> {
  return { content, page: { size: 20, number: 0, totalElements: content.length, totalPages: 1 } };
}

function mountView() {
  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore().accessToken = 'live-access-token';
  return mount(PaymentsView, { global: { plugins: [pinia] } });
}

describe('PaymentsView', () => {
  beforeEach(() => resetModuleState());
  afterEach(() => resetModuleState());

  it('labels a Razorpay card payment with its provider', async () => {
    installFetch(async () => jsonResponse(200, paged([RAZORPAY_ROW])));
    const wrapper = mountView();
    await flushPromises();

    const row = wrapper.find('[data-test="payments-row"]');
    expect(row.text()).toContain('CARD');
    expect(row.text()).toContain('razorpay');
  });

  it('sends the provider filter to the server', async () => {
    const fetch = installFetch(async () => jsonResponse(200, paged([RAZORPAY_ROW])));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('select[aria-label="Filter by provider"]').setValue('RAZORPAY');
    await flushPromises();

    const last = fetch.callsTo('/api/admin/payments').at(-1);
    expect(last?.url).toContain('mode=RAZORPAY');
  });

  it('shows Razorpay references and the refund id in the detail dialog', async () => {
    installFetch(async (req) =>
      req.url.includes(`/api/admin/payments/${RAZORPAY_ROW.id}`)
        ? jsonResponse(200, {
            payment: RAZORPAY_ROW,
            refunds: [
              { id: 'r1', amount: 500, reason: 'Return', createdAt: '2026-11-15T11:00:00Z', providerRefundId: 'rfnd_TEST789' },
            ],
          })
        : jsonResponse(200, paged([RAZORPAY_ROW])),
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-test="payments-row"]').trigger('click');
    await flushPromises();

    const dialog = wrapper.find('[role="dialog"]');
    expect(dialog.text()).toContain('Razorpay order');
    expect(dialog.text()).toContain('order_TEST123');
    expect(dialog.text()).toContain('pay_TEST456');
    expect(dialog.text()).toContain('rfnd_TEST789');
    expect(dialog.text()).not.toContain('Stripe intent');
  });
});
