<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter, RouterLink, RouterView } from 'vue-router';
import {
  Boxes,
  LayoutDashboard,
  LogOut,
  Menu,
  Package,
  ReceiptText,
  RotateCcw,
  Ticket,
  PanelLeftClose,
  PanelLeftOpen,
  Settings as SettingsIcon,
} from '@lucide/vue';

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
}

const NAV_ITEMS: readonly NavItem[] = [
  { name: 'dashboard', label: 'Overview', to: '/dashboard', icon: LayoutDashboard },
  { name: 'products', label: 'Products', to: '/products', icon: Package },
  { name: 'inventory', label: 'Inventory', to: '/inventory', icon: Boxes },
  { name: 'orders', label: 'Orders', to: '/orders', icon: ReceiptText, adminOnly: true },
  { name: 'returns', label: 'Returns', to: '/returns', icon: RotateCcw, adminOnly: true },
  { name: 'coupons', label: 'Coupons', to: '/coupons', icon: Ticket, adminOnly: true },
  { name: 'settings', label: 'Settings', to: '/settings', icon: SettingsIcon },
];

const visibleNavItems = computed<readonly NavItem[]>(() =>
  NAV_ITEMS.filter((item) => !item.adminOnly || auth.isAdmin),
);

const PAGE_TITLES: Readonly<Record<string, string>> = {
  dashboard: 'Overview',
  products: 'Products',
  'new-product': 'New product',
  inventory: 'Inventory',
  orders: 'Orders',
  returns: 'Returns',
  coupons: 'Coupons',
  settings: 'Settings',
};

const pageTitle = computed<string>(() => {
  const name = typeof route.name === 'string' ? route.name : '';
  return PAGE_TITLES[name] ?? 'Admin Product Catalog';
});

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
          <h1 class="text-sm font-semibold">{{ pageTitle }}</h1>
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
        <div class="flex h-14 flex-none items-center px-4">
          <span v-if="!collapsed" class="truncate text-sm font-semibold">Admin Product Catalog</span>
          <span v-else class="text-sm font-semibold" aria-hidden="true">APC</span>
        </div>

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
