/**
 * React Router configuration
 */

import { lazy, Suspense } from 'react'
import { createBrowserRouter } from 'react-router-dom'
import { LoadingBlock } from '../components/Form'
import { Layout } from '../components'
import { RequireAuth } from '../components/RequireAuth'
import { ProductCatalogPage } from '../pages/ProductCatalogPage'
import { ProductDetailPage } from '../pages/ProductDetailPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { WishlistPage } from '../pages/WishlistPage'
import { LoginPage } from '../pages/LoginPage'
import { RegisterPage } from '../pages/RegisterPage'
import { AuthCallbackPage } from '../pages/AuthCallbackPage'
import { ForgotPasswordPage } from '../pages/ForgotPasswordPage'
import { ResetPasswordPage } from '../pages/ResetPasswordPage'
import { VerifyEmailPage } from '../pages/VerifyEmailPage'
import { CheckoutPage } from '../pages/CheckoutPage'
import { OrderPage } from '../pages/OrderPage'
import { AccountLayout } from '../pages/account/AccountLayout'
import { OverviewPage } from '../pages/account/OverviewPage'
import { ProfilePage } from '../pages/account/ProfilePage'
import { SettingsPage } from '../pages/account/SettingsPage'
import { AddressesPage } from '../pages/account/AddressesPage'
import { OrdersPage } from '../pages/account/OrdersPage'

// Heavier or rarely visited routes are split out of the main chunk.
const SellerPage = lazy(() => import('../pages/SellerPage'))
const ComparePage = lazy(() => import('../pages/ComparePage'))
const AlertsPage = lazy(() => import('../pages/account/AlertsPage').then((m) => ({ default: m.AlertsPage })))
const RecentlyViewedPage = lazy(() =>
  import('../pages/account/RecentlyViewedPage').then((m) => ({ default: m.RecentlyViewedPage })),
)

const acct = <K extends string>(loader: () => Promise<Record<K, React.ComponentType>>, key: K) =>
  lazy(() => loader().then((m) => ({ default: m[key] })))
const WalletPage = acct(() => import('../pages/account/WalletPage'), 'WalletPage')
const GiftCardsPage = acct(() => import('../pages/account/GiftCardsPage'), 'GiftCardsPage')
const CoinsPage = acct(() => import('../pages/account/CoinsPage'), 'CoinsPage')
const MembershipPage = acct(() => import('../pages/account/MembershipPage'), 'MembershipPage')
const ReferralsPage = acct(() => import('../pages/account/ReferralsPage'), 'ReferralsPage')
const SubscriptionsPage = acct(() => import('../pages/account/SubscriptionsPage'), 'SubscriptionsPage')
const ListsPage = acct(() => import('../pages/account/ListsPage'), 'ListsPage')
const BuyAgainPage = acct(() => import('../pages/account/BuyAgainPage'), 'BuyAgainPage')
const NotificationsPage = acct(() => import('../pages/account/NotificationsPage'), 'NotificationsPage')
const NotificationSettingsPage = acct(() => import('../pages/account/NotificationSettingsPage'), 'NotificationSettingsPage')
const SharedListPage = acct(() => import('../pages/SharedListPage'), 'SharedListPage')

const lazyElement = (node: React.ReactNode) => <Suspense fallback={<LoadingBlock />}>{node}</Suspense>

export const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      {
        path: '/',
        element: <ProductCatalogPage />,
      },
      {
        path: '/products/:id',
        element: <ProductDetailPage />,
      },
      {
        path: '/p/:slug',
        element: <ProductDetailPage />,
      },
      { path: '/seller/:slug', element: lazyElement(<SellerPage />) },
      { path: '/compare', element: lazyElement(<ComparePage />) },
      { path: '/lists/shared/:token', element: lazyElement(<SharedListPage />) },
      {
        path: '/wishlist',
        element: <WishlistPage />,
      },
      {
        path: '/login',
        element: <LoginPage />,
      },
      {
        path: '/register',
        element: <RegisterPage />,
      },
      { path: '/forgot-password', element: <ForgotPasswordPage /> },
      { path: '/reset-password', element: <ResetPasswordPage /> },
      { path: '/verify-email', element: <VerifyEmailPage /> },
      {
        path: '/auth/callback',
        element: <AuthCallbackPage />,
      },
      {
        element: <RequireAuth />,
        children: [
          { path: '/checkout', element: <CheckoutPage /> },
          { path: '/orders/:id', element: <OrderPage /> },
          {
            path: '/account',
            element: <AccountLayout />,
            children: [
              { index: true, element: <OverviewPage /> },
              { path: 'profile', element: <ProfilePage /> },
              { path: 'addresses', element: <AddressesPage /> },
              { path: 'orders', element: <OrdersPage /> },
              { path: 'recent', element: lazyElement(<RecentlyViewedPage />) },
              { path: 'alerts', element: lazyElement(<AlertsPage />) },
              { path: 'wallet', element: lazyElement(<WalletPage />) },
              { path: 'gift-cards', element: lazyElement(<GiftCardsPage />) },
              { path: 'coins', element: lazyElement(<CoinsPage />) },
              { path: 'membership', element: lazyElement(<MembershipPage />) },
              { path: 'referrals', element: lazyElement(<ReferralsPage />) },
              { path: 'subscriptions', element: lazyElement(<SubscriptionsPage />) },
              { path: 'lists', element: lazyElement(<ListsPage />) },
              { path: 'buy-again', element: lazyElement(<BuyAgainPage />) },
              { path: 'notifications', element: lazyElement(<NotificationsPage />) },
              { path: 'notification-settings', element: lazyElement(<NotificationSettingsPage />) },
              { path: 'settings', element: <SettingsPage /> },
            ],
          },
        ],
      },
      {
        path: '*',
        element: <NotFoundPage />,
      },
    ],
  },
])
