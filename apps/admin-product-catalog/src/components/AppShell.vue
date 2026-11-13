<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter, RouterLink, RouterView } from 'vue-router';
import {
  BookOpen,
  Boxes,
  Coins,
  CreditCard,
  LifeBuoy,
  Repeat,
  ScrollText,
  FolderTree,
  LayoutDashboard,
  MessageSquareText,
  Users,
  LogOut,
  Menu,
  Package,
  ReceiptText,
  RotateCcw,
  Ticket,
  Zap,
  Megaphone,
  Store,
  BadgeCheck,
  Warehouse,
  Banknote,
  Truck,
  Wallet,
  BarChart3,
  Target,
  Star,
  Upload,
  PanelLeftClose,
  PanelLeftOpen,
  Settings as SettingsIcon,
} from '@lucide/vue';

import BrandLogo from '@/components/BrandLogo.vue';
import ThemeToggle from '@/components/ThemeToggle.vue';
import { useAuthStore } from '@/stores/auth';

/**
 * Layout for authenticated screens: a collapsible sidebar (drawer below
 * 768px) plus a slim sticky header over the content column. Purely
 * presentational apart from sign-out, which is a session concern and
 * therefore delegated to the store.
 */
const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const signingOut = ref(false);

interface NavItem {
  readonly name: string;
  readonly label: string;
  readonly to: string;
  readonly icon: typeof LayoutDashboard;
  /** Hidden unless the user holds the ADMIN role (mirrors the route guard). */
  readonly adminOnly?: boolean;
  /** Hidden unless the user can manage the catalog (CATALOG_MANAGER or ADMIN). */
  readonly catalogOnly?: boolean;
  /** Visible only to ROLE_SELLER users (Seller section). */
  readonly sellerOnly?: boolean;
  /** Visible only to signed-in users without the seller role. */
  readonly applicantOnly?: boolean;
  /** Phase 10 RBAC: visible to ADMIN or any of these roles (mirrors the route guard). */
  readonly roles?: readonly string[];
}

const NAV_ITEMS: readonly NavItem[] = [
  { name: 'dashboard', label: 'Overview', to: '/dashboard', icon: LayoutDashboard, roles: ['CATALOG_MANAGER'] },
  { name: 'products', label: 'Products', to: '/products', icon: Package, roles: ['CATALOG_MANAGER'] },
  { name: 'categories', label: 'Categories', to: '/categories', icon: FolderTree, roles: ['CATALOG_MANAGER'] },
  { name: 'inventory', label: 'Inventory', to: '/inventory', icon: Boxes, roles: ['CATALOG_MANAGER'] },
  { name: 'orders', label: 'Orders', to: '/orders', icon: ReceiptText, roles: ['SUPPORT'] },
  { name: 'support-tickets', label: 'Support tickets', to: '/support/tickets', icon: LifeBuoy, roles: ['SUPPORT'] },
  { name: 'support-articles', label: 'Help articles', to: '/support/articles', icon: BookOpen, roles: ['SUPPORT'] },
  { name: 'returns', label: 'Returns', to: '/returns', icon: RotateCcw, roles: ['FINANCE'] },
  { name: 'payments', label: 'Payments', to: '/payments', icon: CreditCard, roles: ['FINANCE'] },
  { name: 'customers', label: 'Users & roles', to: '/customers', icon: Users, adminOnly: true },
  { name: 'reviews', label: 'Reviews', to: '/reviews', icon: MessageSquareText, adminOnly: true },
  { name: 'exchange-verify', label: 'Exchanges', to: '/exchange', icon: Repeat, adminOnly: true },
  { name: 'coupons', label: 'Coupons', to: '/coupons', icon: Ticket, roles: ['FINANCE'] },
  { name: 'deals', label: 'Deals', to: '/deals', icon: Zap, roles: ['CATALOG_MANAGER'] },
  { name: 'campaigns', label: 'Campaigns', to: '/campaigns', icon: Megaphone, adminOnly: true },
  { name: 'admin-sellers', label: 'Sellers', to: '/sellers', icon: BadgeCheck, adminOnly: true },
  { name: 'admin-payouts', label: 'Payouts', to: '/payouts', icon: Banknote, roles: ['FINANCE'] },
  { name: 'admin-warehouses', label: 'Warehouses', to: '/warehouses', icon: Warehouse, roles: ['CATALOG_MANAGER'] },
  { name: 'audit-log', label: 'Audit log', to: '/audit', icon: ScrollText, adminOnly: true },
  { name: 'fx-rates', label: 'FX rates', to: '/fx-rates', icon: Coins, adminOnly: true },
  { name: 'settings', label: 'Settings', to: '/settings', icon: SettingsIcon },
];

/** Separate "Seller" section (Phase 9). */
const SELLER_NAV_ITEMS: readonly NavItem[] = [
  { name: 'seller-apply', label: 'Become a seller', to: '/seller/apply', icon: Store, applicantOnly: true },
  { name: 'seller-listings', label: 'My listings', to: '/seller/listings', icon: Package, sellerOnly: true },
  { name: 'seller-bulk', label: 'Bulk upload', to: '/seller/bulk', icon: Upload, sellerOnly: true },
  { name: 'seller-orders', label: 'Orders to fulfil', to: '/seller/orders', icon: Truck, sellerOnly: true },
  { name: 'seller-finance', label: 'Ledger & payouts', to: '/seller/finance', icon: Wallet, sellerOnly: true },
  { name: 'seller-analytics', label: 'Analytics', to: '/seller/analytics', icon: BarChart3, sellerOnly: true },
  { name: 'seller-ads', label: 'Ads', to: '/seller/ads', icon: Target, sellerOnly: true },
  { name: 'seller-feedback', label: 'Feedback', to: '/seller/feedback', icon: Star, sellerOnly: true },
];

/** Staff-only items (everything except Settings) are hidden from plain customers/sellers. */
const visibleNavItems = computed<readonly NavItem[]>(() =>
  NAV_ITEMS.filter(
    (item) =>
      (item.name === 'settings' || auth.isStaff) &&
      (!item.adminOnly || auth.isAdmin) &&
      (item.roles === undefined || auth.hasAnyRole(...item.roles)) &&
      (!item.catalogOnly || auth.canManageCatalog),
  ),
);

const visibleSellerItems = computed<readonly NavItem[]>(() =>
  SELLER_NAV_ITEMS.filter(
    (item) => (!item.sellerOnly || auth.isSeller) && (!item.applicantOnly || !auth.isSeller),
  ),
);

const PAGE_TITLES: Readonly<Record<string, string>> = {
  dashboard: 'Overview',
  products: 'Products',
  'new-product': 'New product',
  categories: 'Categories',
  inventory: 'Inventory',
  customers: 'Users & roles',
  'customer-detail': 'Customer',
  reviews: 'Reviews',
  payments: 'Payments',
  orders: 'Orders',
  returns: 'Returns',
  coupons: 'Coupons',
  deals: 'Deals',
  campaigns: 'Campaigns',
  settings: 'Settings',
  'seller-apply': 'Become a seller',
  'seller-listings': 'My listings',
  'seller-bulk': 'Bulk upload',
  'seller-orders': 'Orders to fulfil',
  'seller-finance': 'Ledger & payouts',
  'seller-analytics': 'Seller analytics',
  'seller-ads': 'Sponsored ads',
  'seller-feedback': 'Seller feedback',
  'admin-sellers': 'Sellers',
  'admin-payouts': 'Payouts',
  'admin-warehouses': 'Warehouses',
  'support-tickets': 'Support tickets',
  'support-ticket-detail': 'Support ticket',
  'support-articles': 'Help articles',
  'audit-log': 'Audit log',
  'exchange-verify': 'Exchange verification',
  'fx-rates': 'FX rates',
};

const pageTitle = computed<string>(() => {
  const name = typeof route.name === 'string' ? route.name : '';
  return PAGE_TITLES[name] ?? 'Cartwise Admin';
});

// Browser tab: "Orders · Cartwise Admin" so several admin tabs stay distinguishable.
watch(
  pageTitle,
  (title) => {
    document.title = title === 'Cartwise Admin' ? title : `${title} · Cartwise Admin`;
  },
  { immediate: true },
);

function isActive(item: NavItem): boolean {
  return route.path === item.to || route.path.startsWith(`${item.to}/`);
}

/**
 * Collapsed/expanded is a display preference, not session state, so it
 * persists across reloads the same way `useTheme.ts` persists the theme —
 * a plain `localStorage` key, applied eagerly on mount rather than reactive
 * SSR concerns (this app has none).
 */
const SIDEBAR_COLLAPSED_KEY = 'sidebar-collapsed';
const collapsed = ref(false);
const mobileOpen = ref(false);

onMounted(() => {
  collapsed.value = localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === 'true';
});

function toggleCollapsed(): void {
  collapsed.value = !collapsed.value;
  localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(collapsed.value));
}

function closeMobileDrawer(): void {
  mobileOpen.value = false;
}

async function signOut(): Promise<void> {
  signingOut.value = true;
  try {
    await auth.logout();
  } finally {
    signingOut.value = false;
    await router.push({ name: 'login' });
  }
}
</script>

<template>
  <div class="drawer md:drawer-open min-h-screen">
    <input
      id="app-drawer"
      v-model="mobileOpen"
      type="checkbox"
      class="drawer-toggle"
    />

    <div class="drawer-content flex min-h-screen flex-col">
      <header class="app-header sticky top-0 z-10 flex flex-none items-center justify-between px-4 lg:px-6">
        <div class="flex items-center gap-3">
          <label
            for="app-drawer"
            class="btn btn-square btn-ghost btn-sm md:hidden"
            aria-label="Open navigation"
          >
            <Menu class="h-5 w-5" aria-hidden="true" />
          </label>
          <BrandLogo tone="on-brand" collapsed class="md:hidden" />
          <h1 class="text-sm">{{ pageTitle }}</h1>
        </div>
        <div class="flex items-center gap-3">
          <span
            v-if="auth.user"
            class="hidden text-sm opacity-70 lg:inline"
            data-test="current-user"
          >
            {{ auth.user.email }}
          </span>
          <ThemeToggle />
        </div>
      </header>

      <main class="flex-1 p-4 sm:p-6">
        <RouterView />
      </main>
    </div>

    <div class="drawer-side z-20">
      <label for="app-drawer" aria-label="Close navigation" class="drawer-overlay" />
      <aside
        class="app-sidebar"
        :style="{ width: collapsed ? '72px' : '264px' }"
      >
        <RouterLink
          to="/dashboard"
          class="app-brand flex h-14 flex-none items-center px-4"
          :class="{ 'justify-center px-0': collapsed }"
          aria-label="Cartwise Admin: go to overview"
          @click="closeMobileDrawer"
        >
          <BrandLogo tone="on-brand" :collapsed="collapsed" />
        </RouterLink>

        <div>

        </div>
        <nav class="flex-1 overflow-y-auto px-2" aria-label="Primary">
          <RouterLink
            v-for="item in visibleNavItems"
            :key="item.name"
            :to="item.to"
            class="nav-item mb-1 px-3 py-2"
            :class="{ 'nav-item-active': isActive(item) }"
            :data-test="`nav-${item.name}`"
            :title="collapsed ? item.label : undefined"
            @click="closeMobileDrawer"
          >
            <component :is="item.icon" class="h-4 w-4 flex-none" aria-hidden="true" />
            <span v-if="!collapsed" class="truncate">{{ item.label }}</span>
          </RouterLink>
          <template v-if="visibleSellerItems.length > 0">
            <p v-if="!collapsed" class="mb-1 mt-4 px-3 text-xs font-semibold uppercase opacity-50">Seller</p>
            <hr v-else class="my-3 border-base-content/10" />
            <RouterLink
              v-for="item in visibleSellerItems"
              :key="item.name"
              :to="item.to"
              class="nav-item mb-1 px-3 py-2"
              :class="{ 'nav-item-active': isActive(item) }"
              :data-test="`nav-${item.name}`"
              :title="collapsed ? item.label : undefined"
              @click="closeMobileDrawer"
            >
              <component :is="item.icon" class="h-4 w-4 flex-none" aria-hidden="true" />
              <span v-if="!collapsed" class="truncate">{{ item.label }}</span>
            </RouterLink>
          </template>
        </nav>

        <div class="flex-none border-t border-base-content/10 p-3">
          <div class="flex items-center justify-between gap-2">
            <span
              v-if="!collapsed && auth.user"
              class="truncate text-xs opacity-70"
              :title="auth.user.email"
            >
              {{ auth.user.email }}
            </span>
            <button
              class="btn btn-ghost btn-square btn-sm"
              type="button"
              data-test="logout"
              :aria-label="'Sign out'"
              :disabled="signingOut"
              @click="signOut"
            >
              <span v-if="signingOut" class="loading loading-spinner loading-xs" aria-hidden="true" />
              <LogOut v-else class="h-4 w-4" aria-hidden="true" />
            </button>
          </div>
          <button
            class="btn btn-ghost btn-sm mt-2 hidden w-full justify-start gap-2 md:flex"
            type="button"
            data-test="sidebar-collapse-toggle"
            :aria-label="collapsed ? 'Expand sidebar' : 'Collapse sidebar'"
            @click="toggleCollapsed"
          >
            <PanelLeftOpen v-if="collapsed" class="h-4 w-4" aria-hidden="true" />
            <PanelLeftClose v-else class="h-4 w-4" aria-hidden="true" />
            <span v-if="!collapsed">Collapse</span>
          </button>
        </div>
      </aside>
    </div>
  </div>
</template>
