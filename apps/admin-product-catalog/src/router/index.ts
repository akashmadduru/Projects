import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router';

import { createAuthGuard } from '@/router/guards';

/**
 * Every route component is lazy — the login screen must not ship the catalog's
 * code, and an anonymous visitor is the most common first paint.
 */
export const routes: readonly RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/components/AppShell.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/ProductsView.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'products/new',
        name: 'new-product',
        component: () => import('@/views/NewProductView.vue'),
        // Matches the backend's own write-side RBAC (CATALOG_MANAGER/ADMIN) -
        // navigating here directly without the role redirects to /dashboard,
        // the same as the ProductsView's write buttons already being hidden.
        meta: { requiresAuth: true, requiresAnyRole: ['CATALOG_MANAGER', 'ADMIN'] },
      },
      {
        path: 'inventory',
        name: 'inventory',
        component: () => import('@/views/InventoryView.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrdersView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'returns',
        name: 'returns',
        component: () => import('@/views/ReturnsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'coupons',
        name: 'coupons',
        component: () => import('@/views/CouponsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/SettingsView.vue'),
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { guestOnly: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { guestOnly: true },
  },
  {
    path: '/auth/callback',
    name: 'auth-callback',
    component: () => import('@/views/AuthCallbackView.vue'),
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
];

export function createAppRouter(): Router {
  const router = createRouter({
    history: createWebHistory(),
    routes: [...routes],
  });
  const guard = createAuthGuard();
  router.beforeEach((to) => guard(to));
  return router;
}
