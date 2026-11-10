/**
 * React Router configuration
 */

import { createBrowserRouter } from 'react-router-dom'
import { Layout } from '../components'
import { ProductCatalogPage } from '../pages/ProductCatalogPage'
import { ProductDetailPage } from '../pages/ProductDetailPage'
import { NotFoundPage } from '../pages/NotFoundPage'

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
        path: '*',
        element: <NotFoundPage />,
      },
    ],
  },
])
