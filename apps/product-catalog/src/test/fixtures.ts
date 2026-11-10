/**
 * Test fixtures - sample API responses
 */

import type { Product, ProductResponse } from '../types'

export const sampleProduct: Product = {
  id: '1',
  sku: 'SKU-TEST-00001',
  name: 'Sample Product',
  priceAmount: 29.99,
  priceCurrency: 'USD',
  active: true,
  version: 1,
  categoryId: 'ELECTRONICS',
  sellerName: 'Test Seller',
  specifications: {
    feature1: 'value1',
  },
  images: ['https://via.placeholder.com/300'],
  lastKnownQuantity: 100,
}

export const sampleProducts: Product[] = Array.from({ length: 20 }, (_, i) => ({
  ...sampleProduct,
  id: String(i + 1),
  name: `Product ${i + 1}`,
  sku: `SKU-TEST-${String(i + 1).padStart(5, '0')}`,
}))

export const sampleProductResponse: ProductResponse = {
  content: sampleProducts,
  pageable: {
    pageNumber: 0,
    pageSize: 20,
  },
  totalElements: 100,
  totalPages: 5,
  isFirst: true,
  isLast: false,
}

export const productWithZeroPrice: Product = {
  ...sampleProduct,
  priceAmount: 0,
}

export const productWithHighPrice: Product = {
  ...sampleProduct,
  priceAmount: 999.99,
}

export const productWithoutInventory: Product = {
  ...sampleProduct,
  lastKnownQuantity: 0,
}

export const productWithNoStock: Product = {
  ...sampleProduct,
  lastKnownQuantity: 0,
}

export const productWithLimitedStock: Product = {
  ...sampleProduct,
  lastKnownQuantity: 3,
}

export const productWithInactiveStatus: Product = {
  ...sampleProduct,
  active: false,
}

export const productWithNullQuantity: Product = {
  ...sampleProduct,
  lastKnownQuantity: null,
}

export const productWithNullSeller: Product = {
  ...sampleProduct,
  sellerName: null,
}

export const productWithHighQuantity: Product = {
  ...sampleProduct,
  lastKnownQuantity: 10000,
}

// Single product for card testing
export const singleProduct: Product = sampleProduct

// Multiple products for grid testing
export const productsForGrid: Product[] = Array.from({ length: 12 }, (_, i) => ({
  ...sampleProduct,
  id: String(i + 1),
  name: `Grid Product ${i + 1}`,
  sku: `SKU-GRID-${String(i + 1).padStart(5, '0')}`,
  lastKnownQuantity: 50 + Math.floor(Math.random() * 100),
}))
