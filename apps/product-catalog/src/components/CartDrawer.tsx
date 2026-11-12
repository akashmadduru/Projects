/**
 * Mini cart: line items with quantity steppers and a subtotal per currency
 */

import { Link, useNavigate } from 'react-router-dom'
import { Drawer } from './Drawer'
import { CartIcon, MinusIcon, PlusIcon, TrashIcon } from './Icons'
import { ProductImage } from './ProductImage'
import { formatMoney } from '../lib/format'
import { useAuthStore, useCartStore, useUiStore } from '../stores'

export function CartDrawer() {
  const open = useUiStore((s) => s.cartOpen)
  const close = useUiStore((s) => s.closeCart)
  const items = useCartStore((s) => s.items)
  const updateQuantity = useCartStore((s) => s.updateQuantity)
  const removeItem = useCartStore((s) => s.removeItem)
  const authStatus = useAuthStore((s) => s.status)
  const navigate = useNavigate()

  // Items may come from different currencies; never add USD to EUR.
  const subtotals = new Map<string, number>()
  for (const item of items) {
    if (!item.snapshot) continue
    const { priceCurrency, priceAmount } = item.snapshot
    subtotals.set(priceCurrency, (subtotals.get(priceCurrency) ?? 0) + priceAmount * item.quantity)
  }
  const itemCount = items.reduce((sum, item) => sum + item.quantity, 0)

  return (
    <Drawer
      open={open}
      onClose={close}
      title={`Your cart${itemCount ? ` (${itemCount})` : ''}`}
      footer={
        items.length > 0 && (
          <div className="space-y-3">
            {[...subtotals].map(([currency, amount]) => (
              <div key={currency} className="flex items-baseline justify-between">
                <span className="text-on-surface-muted">Subtotal</span>
                <span className="text-xl font-bold text-on-surface">{formatMoney(amount, currency)}</span>
              </div>
            ))}
            <p className="text-xs text-on-surface-muted">Taxes and shipping are calculated at checkout.</p>
            <button
              type="button"
              onClick={() => {
                close()
                navigate(authStatus === 'authenticated' ? '/checkout' : '/login?next=%2Fcheckout')
              }}
              disabled={authStatus === 'unknown'}
              className="press shine bg-cta-gradient w-full rounded-xl py-3 font-semibold text-on-accent shadow-cta disabled:opacity-60"
            >
              {authStatus === 'authenticated' ? 'Checkout' : 'Sign in to check out'}
            </button>
          </div>
        )
      }
    >
      {items.length === 0 ? (
        <div className="flex flex-col items-center gap-3 py-16 text-center">
          <CartIcon className="w-12 h-12 text-on-surface-muted" />
          <p className="font-semibold text-on-surface">Your cart is empty</p>
          <p className="text-sm text-on-surface-muted">Find something you love and add it here.</p>
          <button
            type="button"
            onClick={close}
            className="press shine bg-cta-gradient mt-2 rounded-xl px-5 py-2.5 font-semibold text-on-accent shadow-cta"
          >
            Continue shopping
          </button>
        </div>
      ) : (
        <ul className="divide-y divide-outline">
          {items.map((item) => (
            <li key={item.productId} className="flex gap-3 py-4">
              <Link
                to={`/products/${item.productId}`}
                onClick={close}
                className="h-20 w-20 shrink-0 overflow-hidden rounded-lg bg-surface-variant"
              >
                <ProductImage src={item.snapshot?.image} alt={item.snapshot?.name ?? 'Product'} productId={item.productId} />
              </Link>
              <div className="flex min-w-0 flex-1 flex-col gap-2">
                <Link
                  to={`/products/${item.productId}`}
                  onClick={close}
                  className="line-clamp-2 text-sm font-medium text-on-surface hover:text-primary"
                >
                  {item.snapshot?.name ?? 'Product'}
                </Link>
                {item.snapshot && (
                  <span className="text-sm font-bold text-on-surface">
                    {formatMoney(item.snapshot.priceAmount * item.quantity, item.snapshot.priceCurrency)}
                  </span>
                )}
                <div className="flex items-center justify-between">
                  <div className="flex items-center rounded-lg border border-outline">
                    <button
                      type="button"
                      onClick={() => updateQuantity(item.productId, item.quantity - 1)}
                      className="p-1.5 text-on-surface hover:bg-surface-variant"
                      aria-label={`Decrease quantity of ${item.snapshot?.name ?? 'product'}`}
                    >
                      <MinusIcon className="w-4 h-4" />
                    </button>
                    <span className="min-w-8 text-center text-sm font-semibold" aria-live="polite">
                      {item.quantity}
                    </span>
                    <button
                      type="button"
                      onClick={() => updateQuantity(item.productId, item.quantity + 1)}
                      className="p-1.5 text-on-surface hover:bg-surface-variant"
                      aria-label={`Increase quantity of ${item.snapshot?.name ?? 'product'}`}
                    >
                      <PlusIcon className="w-4 h-4" />
                    </button>
                  </div>
                  <button
                    type="button"
                    onClick={() => removeItem(item.productId)}
                    className="rounded-lg p-1.5 text-on-surface-muted hover:bg-surface-variant hover:text-error"
                    aria-label={`Remove ${item.snapshot?.name ?? 'product'} from cart`}
                  >
                    <TrashIcon className="w-4 h-4" />
                  </button>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Drawer>
  )
}
