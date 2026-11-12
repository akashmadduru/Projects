<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, money } from '@/lib/adminApi';
import { getAnalytics } from '@/lib/sellerApi';
import type { SellerAnalytics } from '@/types/seller';

/** Seller analytics: stat cards, top products, and a daily revenue bar chart in inline SVG (no chart lib). */
function isoDay(d: Date): string {
  return d.toISOString().slice(0, 10);
}
const to = ref(isoDay(new Date()));
const from = ref(isoDay(new Date(Date.now() - 29 * 86_400_000)));

const data = ref<SellerAnalytics | null>(null);
const loading = ref(false);
const error = ref<{ message: string; correlationId: string | null } | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    data.value = await getAnalytics(from.value, to.value);
  } catch (e: unknown) {
    error.value = errorInfo(e);
    data.value = null;
  } finally {
    loading.value = false;
  }
}

const currency = computed<string>(() => data.value?.currency ?? 'INR');

const CHART_W = 720;
const CHART_H = 220;
const PAD = { l: 8, r: 8, t: 12, b: 24 };

const bars = computed(() => {
  const daily = data.value?.daily ?? [];
  const max = Math.max(1, ...daily.map((d) => d.revenue));
  const plotW = CHART_W - PAD.l - PAD.r;
  const plotH = CHART_H - PAD.t - PAD.b;
  const step = daily.length > 0 ? plotW / daily.length : plotW;
  const bw = Math.max(1, step * 0.7);
  return daily.map((d, i) => {
    const h = (d.revenue / max) * plotH;
    return {
      key: d.date,
      x: PAD.l + i * step + (step - bw) / 2,
      y: PAD.t + plotH - h,
      w: bw,
      h,
      label: d.date.slice(5),
      title: `${d.date}: ${money(d.revenue, currency.value)} (${d.orders} orders)`,
      showLabel: daily.length <= 12 || i % Math.ceil(daily.length / 8) === 0,
      cx: PAD.l + i * step + step / 2,
    };
  });
});

function pct(n: number | undefined): string {
  return n === undefined ? '-' : `${(n * 100).toFixed(1)}%`;
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-end justify-between gap-2">
      <h1 class="text-2xl font-semibold">Analytics</h1>
      <form class="flex items-end gap-2" @submit.prevent="load">
        <label class="form-control"><span class="label-text text-xs">From</span><input v-model="from" type="date" class="input input-bordered input-sm" /></label>
        <label class="form-control"><span class="label-text text-xs">To</span><input v-model="to" type="date" class="input input-bordered input-sm" /></label>
        <button class="btn btn-primary btn-sm" type="submit" :disabled="loading">Apply</button>
      </form>
    </div>

    <div v-if="loading" class="data-card p-8 text-center" role="status">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <template v-else-if="data">
      <div class="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4" data-test="analytics-cards">
        <div class="data-card p-3"><div class="text-xs opacity-60">Revenue</div><div class="text-lg font-semibold">{{ money(data.revenue, currency) }}</div></div>
        <div class="data-card p-3"><div class="text-xs opacity-60">Orders</div><div class="text-lg font-semibold">{{ data.orders }}</div></div>
        <div class="data-card p-3"><div class="text-xs opacity-60">Units sold</div><div class="text-lg font-semibold">{{ data.unitsSold }}</div></div>
        <div class="data-card p-3"><div class="text-xs opacity-60">Returns</div><div class="text-lg font-semibold">{{ data.returns }} ({{ pct(data.returnRate) }})</div></div>
        <div class="data-card p-3"><div class="text-xs opacity-60">Avg rating</div><div class="text-lg font-semibold">{{ data.avgRating?.toFixed(2) ?? '-' }}</div></div>
        <div class="data-card p-3"><div class="text-xs opacity-60">Product views</div><div class="text-lg font-semibold">{{ data.views ?? '-' }}</div></div>
        <div class="data-card p-3"><div class="text-xs opacity-60">Conversion</div><div class="text-lg font-semibold">{{ pct(data.conversionRate) }}</div></div>
      </div>

      <div class="data-card mb-4 p-4">
        <h2 class="mb-2 text-lg font-medium">Daily revenue</h2>
        <p v-if="bars.length === 0" class="text-sm opacity-70">No sales in this period.</p>
        <svg
          v-else
          :viewBox="`0 0 ${CHART_W} ${CHART_H}`"
          class="w-full"
          role="img"
          aria-label="Daily revenue bar chart"
          data-test="revenue-chart"
        >
          <line :x1="PAD.l" :x2="CHART_W - PAD.r" :y1="CHART_H - PAD.b" :y2="CHART_H - PAD.b" stroke="currentColor" stroke-opacity="0.2" />
          <g v-for="b in bars" :key="b.key">
            <rect :x="b.x" :y="b.y" :width="b.w" :height="b.h" rx="2" fill="currentColor" fill-opacity="0.75">
              <title>{{ b.title }}</title>
            </rect>
            <text v-if="b.showLabel" :x="b.cx" :y="CHART_H - 8" text-anchor="middle" font-size="10" fill="currentColor" fill-opacity="0.6">{{ b.label }}</text>
          </g>
        </svg>
      </div>

      <div class="data-card p-0">
        <h2 class="px-4 pt-4 text-lg font-medium">Top products</h2>
        <div class="overflow-x-auto">
          <table class="table-minimal table">
            <caption class="sr-only">Top products</caption>
            <thead><tr><th scope="col">Product</th><th scope="col">Units</th><th scope="col">Revenue</th></tr></thead>
            <tbody>
              <tr v-for="p in data.topProducts" :key="p.productId" data-test="top-product-row">
                <td class="text-sm">{{ p.name }}</td>
                <td class="tabular-nums">{{ p.units }}</td>
                <td class="tabular-nums">{{ money(p.revenue, currency) }}</td>
              </tr>
              <tr v-if="data.topProducts.length === 0"><td colspan="3" class="text-sm opacity-70">No data.</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </section>
</template>
