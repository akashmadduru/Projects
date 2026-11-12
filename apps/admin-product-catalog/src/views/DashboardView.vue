<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, type Component } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import {
  Ban,
  Boxes,
  ChevronRight,
  CircleCheck,
  CreditCard,
  FolderTree,
  Hourglass,
  Package,
  PackageCheck,
  PackageX,
  Plus,
  ReceiptText,
  RefreshCw,
  RotateCcw,
  Sparkles,
  Ticket,
  TrendingUp,
  TriangleAlert,
  Truck,
  Users,
  Wallet,
  Zap,
} from '@lucide/vue';

import CountUp from '@/components/CountUp.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import {
  errorInfo,
  fmtDate,
  listOrders,
  lowestStock,
  money,
  orderStats,
  statusBadge,
  userStats,
} from '@/lib/adminApi';
import { apiFetch } from '@/lib/http';
import { useAuthStore } from '@/stores/auth';
import type { Inventory, PagedModel, Product } from '@/types/api';
import type { AdminOrderStats, AdminOrderSummary, AdminUserStats, CurrencyRevenue } from '@/types/commerce';

/**
 * Overview. Every panel loads independently (Promise.allSettled semantics): an order-service
 * outage must not blank the catalog tiles, and a CATALOG_MANAGER - who cannot read admin
 * endpoints - still gets the catalog + stock panels instead of a 403 page.
 *
 * Interactivity: every tile, status row, order row and attention chip deep-links to the screen
 * that explains it (orders/returns accept ?status= / ?q=); revenue toggles between today and
 * 30 days; data refreshes on demand and, optionally, every minute while the tab is visible.
 */
type Panel<T> =
  | { status: 'loading' }
  | { status: 'error'; message: string; correlationId: string | null }
  | { status: 'ok'; data: T };

const AUTO_REFRESH_MS = 60_000;

const auth = useAuthStore();
const router = useRouter();

const catalog = ref<Panel<{ total: number }>>({ status: 'loading' });
const stock = ref<Panel<readonly Inventory[]>>({ status: 'loading' });
const orders = ref<Panel<AdminOrderStats>>({ status: 'loading' });
const recent = ref<Panel<readonly AdminOrderSummary[]>>({ status: 'loading' });
const customers = ref<Panel<AdminUserStats>>({ status: 'loading' });

const pending = ref(0);
const refreshing = computed<boolean>(() => pending.value > 0);
const lastUpdated = ref<Date | null>(null);
const autoRefresh = ref(false);
const range = ref<'today' | '30d'>('today');

/** Loads into a panel; keeps showing the old data during a refresh instead of flashing to blank. */
async function into<T>(target: { value: Panel<T> }, fn: () => Promise<T>): Promise<void> {
  if (target.value.status !== 'ok') target.value = { status: 'loading' };
  pending.value += 1;
  try {
    target.value = { status: 'ok', data: await fn() };
  } catch (e: unknown) {
    target.value = { status: 'error', ...errorInfo(e) };
  } finally {
    pending.value -= 1;
    if (pending.value === 0) lastUpdated.value = new Date();
  }
}

function load(): void {
  void into(catalog, async () => {
    const res = await apiFetch<PagedModel<Product>>('/api/products', { query: { page: 0, size: 1 } });
    return { total: res.page.totalElements };
  });
  void into(stock, async () =>
    (await lowestStock(50)).content.filter((r) => r.quantity <= r.lowStockThreshold).slice(0, 8),
  );
  if (auth.isAdmin) {
    void into(orders, orderStats);
    void into(recent, async () => (await listOrders({ page: 0, size: 5 })).content);
    void into(customers, userStats);
  }
}

let timer: ReturnType<typeof setInterval> | undefined;
function syncTimer(): void {
  if (timer) clearInterval(timer);
  timer = undefined;
  if (autoRefresh.value) {
    timer = setInterval(() => {
      if (!document.hidden && !refreshing.value) load();
    }, AUTO_REFRESH_MS);
  }
}
function toggleAuto(): void {
  autoRefresh.value = !autoRefresh.value;
  syncTimer();
}

onMounted(load);
onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});

// ---- derived numbers -----------------------------------------------------------------------
const byStatus = computed<Readonly<Record<string, number>>>(() =>
  orders.value.status === 'ok' ? orders.value.data.ordersByStatus : {},
);
const returnsBy = computed<Readonly<Record<string, number>>>(() =>
  orders.value.status === 'ok' ? orders.value.data.returnsByStatus : {},
);

const openOrders = computed<number>(() =>
  ['PLACED', 'CONFIRMED', 'PACKED', 'SHIPPED', 'OUT_FOR_DELIVERY'].reduce(
    (sum, k) => sum + (byStatus.value[k] ?? 0),
    0,
  ),
);
const awaitingPayment = computed<number>(() => byStatus.value['PENDING_PAYMENT'] ?? 0);
const returnsNeedingDecision = computed<number>(() => returnsBy.value['REQUESTED'] ?? 0);
const pendingReturns = computed<number>(
  () => returnsNeedingDecision.value + (returnsBy.value['APPROVED'] ?? 0) + (returnsBy.value['PICKED_UP'] ?? 0),
);
const outOfStock = computed<number>(() =>
  stock.value.status === 'ok' ? stock.value.data.filter((r) => r.quantity === 0).length : 0,
);

const revenueRows = computed<readonly CurrencyRevenue[]>(() =>
  orders.value.status !== 'ok'
    ? []
    : range.value === 'today'
      ? orders.value.data.revenueToday
      : orders.value.data.revenueLast30Days,
);

function revenueLine(rows: readonly CurrencyRevenue[]): string {
  return rows.length === 0 ? money(0, 'INR') : rows.map((r) => money(r.amount, r.currency)).join(' · ');
}
const orderCount = computed<number>(() => revenueRows.value.reduce((n, r) => n + r.orderCount, 0));
const aov = computed<string>(() =>
  revenueRows.value.length === 0
    ? '-'
    : revenueRows.value
        .map((r) => money(r.orderCount === 0 ? 0 : r.amount / r.orderCount, r.currency))
        .join(' · '),
);

const statusEntries = computed<readonly [string, number][]>(() =>
  Object.entries(byStatus.value).filter(([, n]) => n > 0),
);
const maxStatus = computed<number>(() => Math.max(1, ...statusEntries.value.map(([, n]) => n)));

const STATUS_ICON: Readonly<Record<string, Component>> = {
  PENDING_PAYMENT: Hourglass,
  PLACED: ReceiptText,
  CONFIRMED: CircleCheck,
  PACKED: Package,
  SHIPPED: Truck,
  OUT_FOR_DELIVERY: Zap,
  DELIVERED: PackageCheck,
  CANCELLED: Ban,
};
const statusIcon = (s: string): Component => STATUS_ICON[s] ?? ReceiptText;
const prettyStatus = (s: string): string =>
  s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase());

// ---- header copy ---------------------------------------------------------------------------
const greeting = computed<string>(() => {
  const h = new Date().getHours();
  const part = h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
  const name = auth.user?.displayName?.split(' ')[0];
  return name ? `${part}, ${name}` : part;
});
const updatedLabel = computed<string>(() =>
  lastUpdated.value ? `Updated ${lastUpdated.value.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}` : 'Loading…',
);

interface QuickAction {
  readonly label: string;
  readonly to: string;
  readonly icon: Component;
  readonly show: boolean;
}
const quickActions = computed<readonly QuickAction[]>(() =>
  [
    { label: 'New product', to: '/products/new', icon: Plus, show: auth.canManageCatalog },
    { label: 'Add category', to: '/categories', icon: FolderTree, show: auth.canManageCatalog },
    { label: 'Orders', to: '/orders', icon: ReceiptText, show: auth.isAdmin },
    { label: 'Returns', to: '/returns', icon: RotateCcw, show: auth.isAdmin },
    { label: 'Payments', to: '/payments', icon: CreditCard, show: auth.isAdmin },
    { label: 'New coupon', to: '/coupons', icon: Ticket, show: auth.isAdmin },
    { label: 'Customers', to: '/customers', icon: Users, show: auth.isAdmin },
    { label: 'Inventory', to: '/inventory', icon: Boxes, show: true },
  ].filter((a) => a.show),
);

interface Attention {
  readonly key: string;
  readonly label: string;
  readonly to: string;
  readonly icon: Component;
  readonly tone: string;
}
const attention = computed<readonly Attention[]>(() => {
  const items: Attention[] = [];
  if (returnsNeedingDecision.value > 0)
    items.push({
      key: 'returns',
      label: `${returnsNeedingDecision.value} return${returnsNeedingDecision.value === 1 ? '' : 's'} need a decision`,
      to: '/returns',
      icon: RotateCcw,
      tone: 'border-warning bg-warning/15',
    });
  if (awaitingPayment.value > 0)
    items.push({
      key: 'pay',
      label: `${awaitingPayment.value} order${awaitingPayment.value === 1 ? '' : 's'} awaiting payment`,
      to: '/orders?status=PENDING_PAYMENT',
      icon: Hourglass,
      tone: 'border-info bg-info/15',
    });
  if (outOfStock.value > 0)
    items.push({
      key: 'oos',
      label: `${outOfStock.value} product${outOfStock.value === 1 ? '' : 's'} out of stock`,
      to: '/inventory',
      icon: PackageX,
      tone: 'border-error bg-error/15',
    });
  return items;
});

function openOrder(id: string): void {
  void router.push({ path: '/orders', query: { q: id } });
}
</script>

<template>
  <section class="flex flex-col gap-6">
    <!-- Header: greeting, freshness, refresh controls -->
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-2xl">{{ greeting }}</h1>
        <p class="flex items-center gap-1.5 text-sm opacity-70" aria-live="polite">
          <Sparkles class="h-4 w-4 text-accent" aria-hidden="true" />
          Here is how the store is doing · {{ updatedLabel }}
        </p>
      </div>
      <div class="flex items-center gap-2">
        <label class="label cursor-pointer gap-2 text-sm">
          <span class="opacity-70">Auto-refresh</span>
          <input
            type="checkbox"
            class="toggle toggle-sm"
            :checked="autoRefresh"
            data-test="dashboard-auto"
            aria-label="Refresh every minute"
            @change="toggleAuto"
          />
        </label>
        <button
          class="btn btn-sm"
          type="button"
          data-test="dashboard-refresh"
          :disabled="refreshing"
          @click="load"
        >
          <RefreshCw class="h-4 w-4" :class="{ 'animate-spin': refreshing }" aria-hidden="true" />
          Refresh
        </button>
      </div>
    </div>

    <!-- Quick actions -->
    <nav aria-label="Quick actions" class="flex flex-wrap gap-2">
      <RouterLink
        v-for="a in quickActions"
        :key="a.to + a.label"
        :to="a.to"
        class="qa press inline-flex items-center gap-2 rounded-full border-2 border-neutral bg-base-100 px-4 py-2 text-sm font-bold"
      >
        <component :is="a.icon" class="h-4 w-4 text-primary" aria-hidden="true" />
        {{ a.label }}
      </RouterLink>
    </nav>

    <!-- Needs attention -->
    <div v-if="attention.length" class="flex flex-wrap items-center gap-2" data-test="dashboard-attention">
      <span class="flex items-center gap-1.5 text-sm font-bold">
        <TriangleAlert class="h-4 w-4 text-warning" aria-hidden="true" /> Needs attention
      </span>
      <RouterLink
        v-for="a in attention"
        :key="a.key"
        :to="a.to"
        class="qa inline-flex items-center gap-2 rounded-full border-2 px-3 py-1 text-sm font-semibold"
        :class="a.tone"
      >
        <component :is="a.icon" class="h-4 w-4" aria-hidden="true" />
        {{ a.label }}
        <ChevronRight class="h-4 w-4" aria-hidden="true" />
      </RouterLink>
    </div>

    <!-- KPI tiles -->
    <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3" data-test="dashboard-stats">
      <div v-if="auth.isAdmin" class="stat-card tile" style="--i: 0">
        <div class="flex items-start justify-between gap-2">
          <span class="icon-badge bg-success/25"><Wallet class="h-5 w-5" aria-hidden="true" /></span>
          <div class="join" role="group" aria-label="Revenue period">
            <button
              type="button"
              class="btn btn-xs join-item"
              :class="{ 'btn-active': range === 'today' }"
              :aria-pressed="range === 'today'"
              @click="range = 'today'"
            >
              Today
            </button>
            <button
              type="button"
              class="btn btn-xs join-item"
              :class="{ 'btn-active': range === '30d' }"
              :aria-pressed="range === '30d'"
              @click="range = '30d'"
            >
              30 days
            </button>
          </div>
        </div>
        <p class="mt-3 text-xs uppercase tracking-wide opacity-60">
          Revenue, {{ range === 'today' ? 'today' : 'last 30 days' }}
        </p>
        <p v-if="orders.status === 'ok'" :key="range" class="pop mt-1 text-2xl font-bold tabular-nums" data-test="stat-revenue-today">
          {{ revenueLine(revenueRows) }}
        </p>
        <p v-else-if="orders.status === 'loading'" class="mt-1"><span class="loading loading-dots loading-sm" /></p>
        <p v-else class="mt-1 text-sm text-error">Unavailable</p>
        <p v-if="orders.status === 'ok'" class="mt-1 flex items-center gap-1 text-xs opacity-70">
          <TrendingUp class="h-3.5 w-3.5" aria-hidden="true" />
          {{ orderCount }} orders · AOV {{ aov }}
        </p>
      </div>

      <RouterLink v-if="auth.isAdmin" to="/orders" class="stat-card tile link-tile" style="--i: 1">
        <span class="icon-badge bg-info/25"><ReceiptText class="h-5 w-5" aria-hidden="true" /></span>
        <p class="mt-3 text-xs uppercase tracking-wide opacity-60">Open orders</p>
        <p class="mt-1 text-3xl font-bold"><CountUp v-if="orders.status === 'ok'" :value="openOrders" /><template v-else>–</template></p>
        <p class="mt-1 text-xs opacity-70">{{ awaitingPayment }} awaiting payment</p>
        <ChevronRight class="chev" aria-hidden="true" />
      </RouterLink>

      <RouterLink v-if="auth.isAdmin" to="/returns" class="stat-card tile link-tile" style="--i: 2">
        <span class="icon-badge bg-warning/25"><RotateCcw class="h-5 w-5" aria-hidden="true" /></span>
        <p class="mt-3 text-xs uppercase tracking-wide opacity-60">Returns in progress</p>
        <p class="mt-1 text-3xl font-bold"><CountUp v-if="orders.status === 'ok'" :value="pendingReturns" /><template v-else>–</template></p>
        <p class="mt-1 text-xs opacity-70">{{ returnsNeedingDecision }} need a decision</p>
        <ChevronRight class="chev" aria-hidden="true" />
      </RouterLink>

      <RouterLink to="/products" class="stat-card tile link-tile" style="--i: 3">
        <span class="icon-badge bg-primary/20"><Package class="h-5 w-5" aria-hidden="true" /></span>
        <p class="mt-3 text-xs uppercase tracking-wide opacity-60">Products</p>
        <p class="mt-1 text-3xl font-bold" data-test="stat-total">
          <CountUp v-if="catalog.status === 'ok'" :value="catalog.data.total" /><template v-else>–</template>
        </p>
        <p class="mt-1 text-xs opacity-70">View the catalog</p>
        <ChevronRight class="chev" aria-hidden="true" />
      </RouterLink>

      <RouterLink to="/inventory" class="stat-card tile link-tile" style="--i: 4">
        <span class="icon-badge bg-error/20"><PackageX class="h-5 w-5" aria-hidden="true" /></span>
        <p class="mt-3 text-xs uppercase tracking-wide opacity-60">Low / out of stock</p>
        <p
          class="mt-1 text-3xl font-bold"
          :class="{ 'text-warning': stock.status === 'ok' && stock.data.length > 0 }"
        >
          <CountUp v-if="stock.status === 'ok'" :value="stock.data.length" /><template v-else>–</template>
        </p>
        <p class="mt-1 text-xs opacity-70">{{ outOfStock }} out of stock · of the 50 lowest rows</p>
        <ChevronRight class="chev" aria-hidden="true" />
      </RouterLink>

      <RouterLink v-if="auth.isAdmin" to="/customers" class="stat-card tile link-tile" style="--i: 5">
        <span class="icon-badge bg-secondary/30"><Users class="h-5 w-5" aria-hidden="true" /></span>
        <p class="mt-3 text-xs uppercase tracking-wide opacity-60">Customers</p>
        <p class="mt-1 text-3xl font-bold">
          <CountUp v-if="customers.status === 'ok'" :value="customers.data.total" /><template v-else>–</template>
        </p>
        <p v-if="customers.status === 'ok'" class="mt-1 text-xs opacity-70">
          {{ customers.data.byStatus['DISABLED'] ?? 0 }} disabled · {{ customers.data.byStatus['LOCKED'] ?? 0 }} locked
        </p>
        <ChevronRight class="chev" aria-hidden="true" />
      </RouterLink>
    </div>

    <ProblemAlert
      v-if="auth.isAdmin && orders.status === 'error'"
      :message="`Order figures unavailable: ${orders.message}`"
      :correlation-id="orders.correlationId"
    />

    <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <!-- Orders by status: each row opens the filtered order list -->
      <div v-if="auth.isAdmin" class="data-card">
        <h2 class="mb-3 flex items-center gap-2 text-sm uppercase tracking-wide opacity-70">
          <ReceiptText class="h-4 w-4" aria-hidden="true" /> Orders by status
        </h2>
        <p v-if="orders.status === 'ok' && statusEntries.length === 0" class="text-sm opacity-70">No orders yet.</p>
        <ul v-else class="flex flex-col gap-1">
          <li v-for="[s, n] in statusEntries" :key="s">
            <RouterLink
              :to="{ path: '/orders', query: { status: s } }"
              class="row-link grid grid-cols-[1.25rem_8.5rem_1fr_2.5rem] items-center gap-3 rounded-xl px-2 py-1.5 text-sm"
              :aria-label="`${prettyStatus(s)}: ${n} orders. Open list`"
            >
              <component :is="statusIcon(s)" class="h-4 w-4 opacity-80" aria-hidden="true" />
              <span class="truncate">{{ prettyStatus(s) }}</span>
              <span class="h-2.5 overflow-hidden rounded-full border border-neutral/40 bg-base-300">
                <span class="bar block h-full rounded-full bg-primary" :style="{ width: `${(n / maxStatus) * 100}%` }" />
              </span>
              <span class="text-right font-semibold tabular-nums">{{ n }}</span>
            </RouterLink>
          </li>
        </ul>
      </div>

      <!-- Recent orders: rows open the order -->
      <div v-if="auth.isAdmin" class="data-card p-0">
        <div class="flex items-baseline justify-between p-4 pb-2">
          <h2 class="flex items-center gap-2 text-sm uppercase tracking-wide opacity-70">
            <Truck class="h-4 w-4" aria-hidden="true" /> Recent orders
          </h2>
          <RouterLink to="/orders" class="link link-hover text-sm">All orders</RouterLink>
        </div>
        <div v-if="recent.status === 'error'" class="p-4">
          <ProblemAlert :message="recent.message" :correlation-id="recent.correlationId" />
        </div>
        <p v-else-if="recent.status === 'ok' && recent.data.length === 0" class="p-4 text-sm opacity-70">No orders yet.</p>
        <table v-else-if="recent.status === 'ok'" class="table-minimal table table-sm">
          <tbody>
            <tr
              v-for="o in recent.data"
              :key="o.id"
              class="cursor-pointer"
              tabindex="0"
              :aria-label="`Open order ${o.id.slice(0, 8)}`"
              @click="openOrder(o.id)"
              @keydown.enter="openOrder(o.id)"
            >
              <td class="font-mono text-xs">{{ o.id.slice(0, 8) }}</td>
              <td class="max-w-[10rem] truncate text-sm">{{ o.email ?? '-' }}</td>
              <td>
                <span class="badge badge-sm gap-1" :class="statusBadge(o.status)">
                  <component :is="statusIcon(o.status)" class="h-3 w-3" aria-hidden="true" />
                  {{ prettyStatus(o.status) }}
                </span>
              </td>
              <td class="text-right tabular-nums">{{ money(o.totalAmount, o.currency) }}</td>
              <td class="text-xs opacity-70">{{ fmtDate(o.placedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="p-6 text-center"><span class="loading loading-spinner" /></div>
      </div>

      <!-- Low stock -->
      <div class="data-card p-0" :class="{ 'lg:col-span-2': !auth.isAdmin }">
        <div class="flex items-baseline justify-between p-4 pb-2">
          <h2 class="flex items-center gap-2 text-sm uppercase tracking-wide opacity-70">
            <PackageX class="h-4 w-4" aria-hidden="true" /> Needs restocking
          </h2>
          <RouterLink to="/inventory" class="link link-hover text-sm">Inventory</RouterLink>
        </div>
        <div v-if="stock.status === 'error'" class="p-4">
          <ProblemAlert :message="stock.message" :correlation-id="stock.correlationId" />
        </div>
        <p v-else-if="stock.status === 'ok' && stock.data.length === 0" class="flex items-center gap-2 p-4 text-sm opacity-80">
          <CircleCheck class="h-4 w-4 text-success" aria-hidden="true" /> Every product is above its low-stock threshold.
        </p>
        <ul v-else-if="stock.status === 'ok'" class="flex flex-col px-2 pb-2">
          <li v-for="r in stock.data" :key="r.id">
            <RouterLink to="/inventory" class="row-link flex items-center gap-3 rounded-xl px-2 py-2 text-sm">
              <component
                :is="r.quantity === 0 ? PackageX : TriangleAlert"
                class="h-4 w-4 shrink-0"
                :class="r.quantity === 0 ? 'text-error' : 'text-warning'"
                aria-hidden="true"
              />
              <span class="min-w-0 flex-1 truncate">{{ r.productName ?? r.productId.slice(0, 8) }}</span>
              <span class="hidden font-mono text-xs opacity-60 sm:inline">{{ r.productSku ?? '' }}</span>
              <span class="h-2 w-20 overflow-hidden rounded-full border border-neutral/40 bg-base-300" aria-hidden="true">
                <span
                  class="bar block h-full rounded-full"
                  :class="r.quantity === 0 ? 'bg-error' : 'bg-warning'"
                  :style="{ width: `${Math.max(4, Math.min(100, (r.quantity / Math.max(r.lowStockThreshold, 1)) * 100))}%` }"
                />
              </span>
              <span class="badge badge-sm" :class="r.quantity === 0 ? 'badge-error' : 'badge-warning'">
                {{ r.quantity }} / {{ r.lowStockThreshold }}
              </span>
            </RouterLink>
          </li>
        </ul>
        <div v-else class="p-6 text-center"><span class="loading loading-spinner" /></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.icon-badge {
  display: inline-flex;
  height: 2.5rem;
  width: 2.5rem;
  align-items: center;
  justify-content: center;
  border-radius: 9999px;
  border: 2px solid var(--color-neutral);
  transition: transform 500ms var(--ease-spring, ease);
}

.tile {
  position: relative;
  animation: rise 600ms var(--ease-smooth, ease) both;
  animation-delay: calc(var(--i, 0) * 70ms);
}

.link-tile {
  display: block;
}

.link-tile .chev {
  position: absolute;
  right: 1rem;
  top: 1.25rem;
  height: 1.25rem;
  width: 1.25rem;
  opacity: 0.4;
  transition:
    transform 500ms var(--ease-smooth, ease),
    opacity 500ms;
}

.link-tile:hover .chev,
.link-tile:focus-visible .chev {
  opacity: 1;
  transform: translateX(4px);
}

.link-tile:hover .icon-badge,
.link-tile:focus-visible .icon-badge {
  transform: rotate(-8deg) scale(1.1);
}

.qa {
  transition:
    transform 400ms var(--ease-spring, ease),
    box-shadow 400ms,
    background-color 400ms;
  box-shadow: 2px 2px 0 var(--cw-ink);
}

.qa:hover,
.qa:focus-visible {
  transform: translateY(-2px);
  box-shadow: 4px 4px 0 var(--cw-ink);
}

.qa:active {
  transform: translate(2px, 2px);
  box-shadow: 0 0 0 var(--cw-ink);
}

.row-link {
  transition: background-color 300ms;
}

.row-link:hover,
.row-link:focus-visible {
  background: color-mix(in oklab, var(--color-primary) 12%, transparent);
}

.bar {
  transform-origin: left;
  animation: grow 900ms var(--ease-smooth, ease) both;
}

.pop {
  animation: pop 500ms var(--ease-spring, ease);
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
}

@keyframes grow {
  from {
    transform: scaleX(0);
  }
}

@keyframes pop {
  from {
    opacity: 0;
    transform: scale(0.94);
  }
}
</style>
