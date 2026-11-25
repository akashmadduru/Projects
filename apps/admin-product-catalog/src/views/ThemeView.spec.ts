import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { THEME_PRESETS } from '@/lib/themePresets';

vi.mock('vue-router', async (orig) => ({ ...(await orig<typeof import('vue-router')>()), onBeforeRouteLeave: vi.fn() }));
vi.mock('@/lib/trustApi', () => ({
  getThemeConfig: vi.fn(async () => ({ config: null, updatedAt: null })),
  saveThemeConfig: vi.fn(),
  resetThemeConfig: vi.fn(),
}));

import ThemeView from '@/views/ThemeView.vue';

const look = THEME_PRESETS.find((p) => p.id === 'neon-arcade')!;

async function mountView() {
  const wrapper = mount(ThemeView, { attachTo: document.body });
  await flushPromises();
  await wrapper.get('[data-test="tab-looks"]').trigger('click');
  return wrapper;
}

describe('ThemeView try-on, undo and guided flow', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  it('hovering a look previews it without changing the draft, and leaving restores the preview', async () => {
    const wrapper = await mountView();
    const card = wrapper.get(`[data-test="preset-${look.id}"]`);
    await card.trigger('mouseenter');
    expect(wrapper.get('[data-test="trial"]').text()).toContain(look.label);
    expect(wrapper.get('[data-test="status"]').text()).toContain('Up to date'); // draft untouched

    await card.trigger('mouseleave');
    vi.advanceTimersByTime(200);
    await flushPromises();
    expect(wrapper.get('[data-test="trial"]').text()).not.toContain(look.label);
    wrapper.unmount();
  });

  it('clicking applies the look, ends the try-on, and the toast offers a working Undo', async () => {
    const wrapper = await mountView();
    const card = wrapper.get(`[data-test="preset-${look.id}"]`);
    await card.trigger('mouseenter');
    await card.trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-test="status"]').text()).toContain('Unpublished changes');
    expect(wrapper.get('[data-test="trial"]').text()).not.toContain('Trying');
    expect(wrapper.get('[data-test="notice"]').text()).toContain(look.label);

    // Undo straight away, inside the history debounce window: must still go back exactly one step.
    await wrapper.get('[data-test="notice-undo"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-test="status"]').text()).toContain('Up to date');
    expect(wrapper.find('[data-test="notice"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('toasts dismiss themselves', async () => {
    const wrapper = await mountView();
    await wrapper.get(`[data-test="preset-${look.id}"]`).trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="notice"]').exists()).toBe(true);
    vi.advanceTimersByTime(8100);
    await flushPromises();
    expect(wrapper.find('[data-test="notice"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('option groups preview on focus and the footer walks through the tabs', async () => {
    const wrapper = await mountView();
    await wrapper.get('[data-test="next-tab"]').trigger('click'); // looks -> brand
    await wrapper.get('[data-test="next-tab"]').trigger('click'); // -> colours
    await wrapper.get('[data-test="next-tab"]').trigger('click'); // -> typography
    await wrapper.get('[data-test="next-tab"]').trigger('click'); // -> borders & shadows
    expect(wrapper.get('[data-test="tab-shape"]').attributes('aria-current')).toBe('step');

    await wrapper.get('[data-test="radius-pill"]').trigger('focus');
    expect(wrapper.get('[data-test="trial"]').text()).toContain('Corners: Pillowy');
    await wrapper.get('[data-test="radius-pill"]').trigger('click');
    expect(wrapper.get('[data-test="radius-pill"]').attributes('aria-pressed')).toBe('true');

    await wrapper.get('[data-test="prev-tab"]').trigger('click');
    expect(wrapper.get('[data-test="tab-type"]').attributes('aria-current')).toBe('step');
    wrapper.unmount();
  });

  it('the compact section menu lists every section, jumps, and closes on Escape', async () => {
    const wrapper = await mountView();
    const trigger = wrapper.get('[data-test="section-menu"]');
    expect(trigger.text()).toContain('Looks');
    expect(trigger.text()).toContain('2 of 10');

    await trigger.trigger('click');
    expect(trigger.attributes('aria-expanded')).toBe('true');
    expect(wrapper.findAll('[data-test^="menu-tab-"]')).toHaveLength(10);
    await wrapper.get('[data-test="menu-tab-components"]').trigger('click');
    expect(wrapper.find('#theme-section-menu').exists()).toBe(false);
    expect(wrapper.get('[data-test="tab-components"]').attributes('aria-current')).toBe('step');
    expect(trigger.text()).toContain('Cards & buttons');

    await trigger.trigger('click');
    await wrapper.get('#theme-section-menu').trigger('keydown', { key: 'Escape' });
    expect(wrapper.find('#theme-section-menu').exists()).toBe(false);
    wrapper.unmount();
  });
});
