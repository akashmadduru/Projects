/**
 * Mock Service Worker handlers for API endpoints
 */

import { http, HttpResponse } from 'msw'
import { backendProductPage, sampleProductResponse } from './fixtures'

export const handlers = [
  http.get('/api/products', () => {
    return HttpResponse.json(backendProductPage, { status: 200 })
  }),

  http.get('/api/products/:id', ({ params }) => {
    const productId = params['id']
    const product = backendProductPage.content.find((p) => p.id === productId)
    if (!product) {
      return HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: `Product ${productId} not found`,
        },
        { status: 404 },
      )
    }
    return HttpResponse.json(product, { status: 200 })
  }),

  http.get('/api/products/catalog/aggregate', () => {
    return HttpResponse.json(
      {
        status: 'success',
        data: {
          products: sampleProductResponse,
          categories: ['Electronics', 'Clothing', 'Books'],
          stats: {
            totalProducts: 100,
            availableProducts: 95,
          },
        },
      },
      { status: 200 },
    )
  }),
]
