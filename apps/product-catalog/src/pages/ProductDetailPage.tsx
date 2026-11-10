/**
 * Full product detail page
 */

import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { PriceDisplay, ErrorAlert, StockStatus, ProductCard } from '../components'
import { useProducts } from '../hooks'
import { dummyProducts } from '../lib/dummyProducts'
import type { Product } from '../types'

export function ProductDetailPage() {
  const { id } = useParams<{ id: string }>()
  const productState = useProducts()
  const [quantity, setQuantity] = useState(1)

  const productList = productState.status === 'success' ? productState.data.content : dummyProducts
  const product = productList.find((p: Product) => p.id === id)

  if (!product) {
    return (
      <div className="max-w-7xl mx-auto px-4 py-12">
        <Link to="/" className="inline-flex items-center gap-2 text-on-surface hover:text-on-surface/80 mb-8 group">
          <svg className="w-4 h-4 transition-transform group-hover:-translate-x-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          Back to Catalog
        </Link>
        <ErrorAlert message="Product not found" />
      </div>
    )
  }

  const imageUrl = product.images?.[0] || 'https://via.placeholder.com/400x500?text=No+Image'
  const relatedProducts = productList
    .filter((p: Product) => p.categoryId === product.categoryId && p.id !== product.id)
    .slice(0, 4)

  // Determine stock status
  const handleAddToCart = () => {
    // TODO: Implement add to cart
  }


  return (
    <div className="min-h-screen bg-surface">
      {/* Breadcrumb Navigation */}
      <div className="max-w-7xl mx-auto px-4 py-4">
        <Link to="/" className="inline-flex items-center gap-2 text-on-surface hover:text-on-surface/80 mb-8 group">
          <svg className="w-4 h-4 transition-transform group-hover:-translate-x-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          Back to Catalog
        </Link>
      </div>

      <div className="max-w-7xl mx-auto px-4 pb-16">
        <div className="grid lg:grid-cols-2 gap-12 mb-16">
          {/* Product Gallery */}
          <div className="space-y-4">
            <div className="relative w-full aspect-square bg-surface-variant rounded-lg overflow-hidden border border-outline/10 group">
              <img
                src={imageUrl}
                alt={product.name}
                className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
              />
              <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-300" style={{background: 'linear-gradient(to top, hsl(var(--color-outline) / 0.1), transparent, transparent)'}} />
            </div>
            {/* Thumbnail indicator */}
            <div className="text-center text-sm text-on-surface-muted">
              1 / {product.images?.length || 1} Image{product.images?.length ? 's' : ''}
            </div>
          </div>

          {/* Product Info */}
          <div className="space-y-6">
            {/* Category */}
            {product.categoryId && (
              <div>
                <p className="text-xs font-medium text-on-surface-muted uppercase tracking-wide">
                  {product.categoryId}
                </p>
              </div>
            )}

            {/* Title */}
            <h1 className="text-4xl font-bold text-on-surface">
              {product.name}
            </h1>

            {/* Price */}
            <div className="pt-4 border-t border-outline/10">
              <PriceDisplay amount={product.priceAmount} currency={product.priceCurrency} size="lg" />
            </div>

            {/* Stock Status */}
            <div className="pt-2">
              {product.lastKnownQuantity !== null && (
                <StockStatus quantity={product.lastKnownQuantity} />
              )}
            </div>

            {/* Quantity and Actions */}
            <div className="pt-6 space-y-4">
              <div className="flex items-center gap-4">
                <div className="flex items-center border border-outline/20 rounded-lg">
                  <button
                    onClick={() => setQuantity(Math.max(1, quantity - 1))}
                    className="px-4 py-2 text-on-surface hover:bg-surface-variant transition-colors"
                    disabled={quantity <= 1}
                  >
                    −
                  </button>
                  <span className="px-6 py-2 text-on-surface font-medium">{quantity}</span>
                  <button
                    onClick={() => setQuantity(Math.min(product.lastKnownQuantity || 1, quantity + 1))}
                    className="px-4 py-2 text-on-surface hover:bg-surface-variant transition-colors"
                    disabled={!product.lastKnownQuantity || quantity >= product.lastKnownQuantity}
                  >
                    +
                  </button>
                </div>

              </div>

              {/* Add to Cart Button */}
              <button
                onClick={handleAddToCart}
                disabled={!product.lastKnownQuantity || product.lastKnownQuantity === 0}
                className="w-full py-3 bg-on-surface text-surface font-semibold rounded-lg hover:bg-on-surface/90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z" />
                </svg>
                {product.lastKnownQuantity && product.lastKnownQuantity > 0 ? 'Add to Cart' : 'Out of Stock'}
              </button>
            </div>

            {/* Additional Info */}
            <div className="pt-6 border-t border-outline/10 space-y-3 text-sm">
              <div className="flex justify-between text-on-surface-muted">
                <span>SKU:</span>
                <span className="font-medium text-on-surface font-mono">{product.sku}</span>
              </div>
              <div className="flex justify-between text-on-surface-muted">
                <span>Availability:</span>
                <span className="font-medium text-on-surface">
                  {product.lastKnownQuantity && product.lastKnownQuantity > 0 ? 'In Stock' : 'Out of Stock'}
                </span>
              </div>
              <div className="flex justify-between text-on-surface-muted">
                <span>Product Version:</span>
                <span className="font-medium text-on-surface">v{product.version}</span>
              </div>
            </div>
          </div>
        </div>

        {/* Related Products */}
        {relatedProducts.length > 0 && (
          <div className="pt-12 border-t border-outline/10">
            <h2 className="text-2xl font-bold text-on-surface mb-8">Related Products</h2>
            <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-6">
              {relatedProducts.map((p) => (
                <ProductCard key={p.id} product={p} />
              ))}
            </div>
          </div>
        )}

      </div>
    </div>
  )
}
