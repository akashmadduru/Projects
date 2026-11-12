/**
 * React Router configuration
 */

import { createBrowserRouter } from 'react-router-dom'
import { Layout } from '../components'
import { RequireAuth } from '../components/RequireAuth'
import { ProductCatalogPage } from '../pages/ProductCatalogPage'
import { ProductDetailPage } from '../pages/ProductDetailPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { WishlistPage } from '../pages/WishlistPage'
import { LoginPage } from '../pages/LoginPage'
import { RegisterPage } from '../pages/RegisterPage'
import { AuthCallbackPage } from '../pages/AuthCallbackPage'
import { CheckoutPage } from '../pages/CheckoutPage'
import { OrderPage } from '../pages/OrderPage'
import { AccountLayout } from '../pages/account/AccountLayout'
import { OverviewPage } from '../pages/account/OverviewPage'
import { ProfilePage } from '../pages/account/ProfilePage'
import { SettingsPage } from '../pages/account/SettingsPage'
import { AddressesPage } from '../pages/account/AddressesPage'
import { OrdersPage } from '../pages/account/OrdersPage'

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
