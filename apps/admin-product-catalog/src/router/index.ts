import { createRouter, createWebHistory, type Router, type RouteRecordRaw } from 'vue-router';

import { createAuthGuard } from '@/router/guards';

/**
 * Every route component is lazy — the login screen must not ship the catalog's
 * code, and an anonymous visitor is the most common first paint.
 */
const STAFF_ROLES = ['ADMIN', 'CATALOG_MANAGER'] as const;
const SELLER_ROLES = ['SELLER'] as const;

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
        meta: { requiresAuth: true, requiresAnyRole: STAFF_ROLES },
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/ProductsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: STAFF_ROLES },
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
        path: 'categories',
        name: 'categories',
        component: () => import('@/views/CategoriesView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: STAFF_ROLES },
      },
      {
        path: 'inventory',
        name: 'inventory',
        component: () => import('@/views/InventoryView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: STAFF_ROLES },
      },
      {
        path: 'customers',
        name: 'customers',
        component: () => import('@/views/CustomersView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'customers/:id',
        name: 'customer-detail',
        component: () => import('@/views/CustomerDetailView.vue'),
        props: true,
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'reviews',
        name: 'reviews',
        component: () => import('@/views/ReviewsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'payments',
        name: 'payments',
        component: () => import('@/views/PaymentsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'FINANCE'] },
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrdersView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'SUPPORT'] },
      },
      {
        path: 'returns',
        name: 'returns',
        component: () => import('@/views/ReturnsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'FINANCE'] },
      },
      {
        path: 'coupons',
        name: 'coupons',
        component: () => import('@/views/CouponsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'FINANCE'] },
      },
      {
        path: 'deals',
        name: 'deals',
        component: () => import('@/views/DealsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'CATALOG_MANAGER'] },
      },
      {
        path: 'campaigns',
        name: 'campaigns',
        component: () => import('@/views/CampaignsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'seller/apply',
        name: 'seller-apply',
        component: () => import('@/views/SellerApplyView.vue'),
        meta: { requiresAuth: true },
      },
      { path: 'seller', redirect: '/seller/listings' },
      {
        path: 'seller/listings',
        name: 'seller-listings',
        component: () => import('@/views/SellerListingsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: SELLER_ROLES },
      },
      {
        path: 'seller/bulk',
        name: 'seller-bulk',
        component: () => import('@/views/SellerBulkView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: SELLER_ROLES },
      },
      {
        path: 'seller/orders',
        name: 'seller-orders',
        component: () => import('@/views/SellerOrdersView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: SELLER_ROLES },
      },
      {
        path: 'seller/finance',
        name: 'seller-finance',
        component: () => import('@/views/SellerFinanceView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: SELLER_ROLES },
      },
      {
        path: 'seller/analytics',
        name: 'seller-analytics',
        component: () => import('@/views/SellerAnalyticsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: SELLER_ROLES },
      },
      {
        path: 'seller/ads',
        name: 'seller-ads',
        component: () => import('@/views/SellerAdsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: SELLER_ROLES },
      },
      {
        path: 'seller/feedback',
        name: 'seller-feedback',
        component: () => import('@/views/SellerFeedbackView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: SELLER_ROLES },
      },
      {
        path: 'sellers',
        name: 'admin-sellers',
        component: () => import('@/views/AdminSellersView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'payouts',
        name: 'admin-payouts',
        component: () => import('@/views/AdminPayoutsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'FINANCE'] },
      },
      {
        path: 'warehouses',
        name: 'admin-warehouses',
        component: () => import('@/views/AdminWarehousesView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'CATALOG_MANAGER'] },
      },
      {
        path: 'support/tickets',
        name: 'support-tickets',
        component: () => import('@/views/SupportTicketsView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'SUPPORT'] },
      },
      {
        path: 'support/tickets/:id',
        name: 'support-ticket-detail',
        component: () => import('@/views/SupportTicketDetailView.vue'),
        props: true,
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'SUPPORT'] },
      },
      {
        path: 'support/articles',
        name: 'support-articles',
        component: () => import('@/views/SupportArticlesView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN', 'SUPPORT'] },
      },
      {
        path: 'audit',
        name: 'audit-log',
        component: () => import('@/views/AuditLogView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'exchange',
        name: 'exchange-verify',
        component: () => import('@/views/ExchangeVerifyView.vue'),
        meta: { requiresAuth: true, requiresAnyRole: ['ADMIN'] },
      },
      {
        path: 'fx-rates',
        name: 'fx-rates',
        component: () => import('@/views/FxRatesView.vue'),
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
