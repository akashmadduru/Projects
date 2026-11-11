/**
 * Test fixtures - sample API responses
 */

import type {
  BackendPagedModel,
  BackendProductResponse,
  Product,
  ProductResponse,
} from '../types'
import { EMPTY_ENRICHMENT } from '../lib/dummyProducts'

export const sampleProduct: Product = {
  ...EMPTY_ENRICHMENT,
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

// Backend wire format (GET /api/products row), taken from a real product-service response
export const backendBook: BackendProductResponse = {
  id: 'eeeeeeee-eeee-eeee-eeee-000000000004',
  sku: 'SKU-BOOK-0004',
  name: 'The Midnight Library',
  priceAmount: 16.99,
  priceCurrency: 'USD',
  active: true,
  version: 0,
  categoryId: '11111111-1111-1111-1111-111111111212',
  sellerName: 'BookStore',
  specifications: null,
  images: [],
  lastKnownQuantity: null,
  description: 'The Midnight Library, sold by BookStore.',
  listPrice: 18.69,
  salePrice: 16.99,
  discountPercent: 9.1,
  thumbnailUrl: 'https://images.unsplash.com/photo-1507842217343-583f20270319?w=400',
  videoUrls: null,
  view360Url: null,
  weightGrams: null,
  dimensionsLength: null,
  dimensionsWidth: null,
  dimensionsHeight: null,
  color: null,
  material: 'Paper',
  averageRating: 4.5,
  ratingCount: 75,
  reviewCount: 55,
  ratingDistribution: null,
  sellerRating: 4.4,
  featured: false,
  sellerVerified: true,
}

// Spring PagedModel envelope, as GET /api/products returns it
export const backendProductPage: BackendPagedModel<BackendProductResponse> = {
  content: [
    backendBook,
    {
      ...backendBook,
      id: 'eeeeeeee-eeee-eeee-eeee-000000000005',
      sku: 'SKU-BOOK-0005',
      images: [
        { id: 'i2', url: 'https://img.example/second.png', displayOrder: 1 },
        { id: 'i1', url: 'https://img.example/first.png', displayOrder: 0 },
      ],
    },
  ],
  page: { size: 20, number: 0, totalElements: 100, totalPages: 5 },
}
