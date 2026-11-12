/**
 * Account dashboard: at-a-glance stats, profile completeness, recent orders, default address,
 * wishlist and cart. Every card loads independently so one failing service never blanks the page.
 */

import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { Starburst } from '../../components/Atomic'
import { Avatar } from '../../components/account/Avatar'
import { CompletenessRing } from '../../components/account/CompletenessRing'
import { CardError, CardSkeleton, SectionCard, viewAllLink } from '../../components/account/SectionCard'
import { StatCard } from '../../components/account/StatCard'
import { formatAddressLines } from '../../components/AddressForm'
import {
  ArrowRightIcon,
  CartIcon,
  CheckIcon,
  ChevronRightIcon,
  HeartIcon,
  MapPinIcon,
  PackageIcon,
  TagIcon,
  UserIcon,
} from '../../components/Icons'
import { ProductImage } from '../../components/ProductImage'
import { useLoad } from '../../hooks/useLoad'
import { displayNameOf, greeting, orderStats, profileCompleteness } from '../../lib/account'
import { errorMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { orderApi } from '../../services/orderApi'
import { userApi } from '../../services/userApi'
import { useAuthStore, useCartStore, useUiStore, useWishlistStore } from '../../stores'
import type { OrderSummary } from '../../types'

const ORDERS_SAMPLE = 50 // order-service's maximum page size
const stagger = (i: number): React.CSSProperties => ({
  animation: `slideUpStagger 0.9s cubic-bezier(0.22,1,0.36,1) ${i * 0.08}s both`,
})

const primaryLink =
  'press shine bg-cta-gradient inline-flex items-center gap-2 rounded-full border-2 border-ink px-5 py-2.5 text-sm font-bold text-on-accent shadow-cta'
const secondaryLink =
  'press inline-flex items-center gap-2 rounded-full border-2 border-ink bg-surface px-5 py-2.5 text-sm font-bold text-on-surface shadow-cta hover:bg-surface-variant'

function StatusPill({ status }: { status: OrderSummary['status'] }) {
  const placed = status === 'PLACED'
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border-2 border-ink px-2.5 py-0.5 text-xs font-bold ${
        placed ? 'bg-success/20 text-success' : 'bg-error/15 text-error'
      }`}
    >
      <span className={`h-2 w-2 rounded-full ${placed ? 'bg-success' : 'bg-error'}`} aria-hidden="true" />
      {placed ? 'Placed' : 'Cancelled'}
    </span>
  )
}

export function OverviewPage() {
  const user = useAuthStore((s) => s.user)
  const openCart = useUiStore((s) => s.openCart)
  const wishlist = useWishlistStore((s) => s.items)
  const cartItems = useCartStore((s) => s.items)

  const loadProfile = useCallback(() => userApi.getProfile(), [])
  const loadAddresses = useCallback(() => userApi.listAddresses(), [])
  const loadOrders = useCallback(() => orderApi.list(0, ORDERS_SAMPLE), [])
  const profile = useLoad(loadProfile)
  const addresses = useLoad(loadAddresses)
  const orders = useLoad(loadOrders)

  if (!user) return null

  const name = displayNameOf({ displayName: profile.data?.displayName ?? user.displayName, email: user.email })
  const stats = orders.data ? orderStats(orders.data.content, orders.data.page.totalElements) : null
  const completeness = profileCompleteness({
    displayName: profile.data?.displayName ?? user.displayName,
    phone: profile.data?.phone,
    emailVerified: user.emailVerified,
    addressCount: addresses.data ? addresses.data.length : null,
  })

  const cartCount = cartItems.reduce((sum, item) => sum + item.quantity, 0)
  const cartCurrencies = new Set(cartItems.map((item) => item.snapshot?.priceCurrency).filter(Boolean))
  const cartSubtotal =
    cartCurrencies.size === 1
      ? cartItems.reduce((sum, item) => sum + (item.snapshot?.priceAmount ?? 0) * item.quantity, 0)
      : null
  const cartCurrency = [...cartCurrencies][0]

  const defaultAddress = addresses.data?.find((a) => a.isDefault) ?? addresses.data?.[0]
  const recentOrders = orders.data?.content.slice(0, 4) ?? []
  const firstSpend = stats?.spend[0]
  const firstName = name.split(/\s+/)[0] ?? name

  return (
    <div className="space-y-6">
      <div style={stagger(0)} className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="font-display text-3xl text-on-surface">
            {greeting()}, <span className="text-gradient-brand">{firstName}</span>
          </h2>
          <p className="text-on-surface-muted">Here&apos;s what&apos;s happening with your account.</p>
        </div>
        <div className="flex flex-wrap gap-3">
          <Link to="/" className={primaryLink}>
            Continue shopping <ArrowRightIcon className="h-4 w-4" />
          </Link>
          <Link to="/account/profile" className={secondaryLink}>
            <UserIcon className="h-4 w-4" /> Edit profile
          </Link>
        </div>
      </div>

      <ul style={stagger(1)} className="grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-3">
        <li>
          <StatCard
            label="Orders"
            icon={<PackageIcon className="h-5 w-5" />}
            hue="vivid-sky"
            value={stats ? stats.orders : null}
            loading={orders.loading && !orders.data}
            failed={!!orders.error}
            caption={
              stats && stats.complete && stats.orders > 0
                ? `${stats.placed} placed · ${stats.cancelled} cancelled`
                : stats && stats.orders === 0
                  ? 'No orders yet'
                  : undefined
            }
            to="/account/orders"
          />
        </li>
        <li>
          <StatCard
            label={stats && !stats.complete ? `Spent (latest ${ORDERS_SAMPLE})` : 'Lifetime spend'}
            icon={<TagIcon className="h-5 w-5" />}
            hue="vivid-lime"
            value={firstSpend ? firstSpend.amount : null}
            format={(n) => formatMoney(n, firstSpend?.currency ?? 'USD')}
            placeholder="—"
            loading={orders.loading && !orders.data}
            failed={!!orders.error}
            caption={
              !stats
                ? undefined
                : stats.spend.length === 0
                  ? 'Nothing spent yet'
                  : stats.spend.length > 1
                    ? `+${stats.spend.length - 1} more ${stats.spend.length === 2 ? 'currency' : 'currencies'}`
                    : 'Excludes cancelled orders'
            }
            to="/account/orders"
          />
        </li>
        <li>
          <StatCard
            label="Saved addresses"
            icon={<MapPinIcon className="h-5 w-5" />}
            hue="vivid-amber"
            value={addresses.data ? addresses.data.length : null}
            loading={addresses.loading && !addresses.data}
            failed={!!addresses.error}
            caption={defaultAddress ? `Default: ${defaultAddress.label || defaultAddress.city}` : 'Add one to check out faster'}
            to="/account/addresses"
          />
        </li>
        <li>
          <StatCard
            label="Wishlist"
            icon={<HeartIcon className="h-5 w-5" />}
            hue="vivid-pink"
            value={wishlist.length}
            caption={wishlist.length === 0 ? 'Nothing saved yet' : 'Saved for later'}
            to="/wishlist"
          />
        </li>
        <li>
          <StatCard
            label="In your cart"
            icon={<CartIcon className="h-5 w-5" />}
            hue="vivid-coral"
            value={cartCount}
            caption={
              cartCount === 0
                ? 'Your cart is empty'
                : cartSubtotal !== null && cartCurrency
                  ? `Subtotal ${formatMoney(cartSubtotal, cartCurrency)}`
                  : undefined
            }
            onClick={openCart}
          />
        </li>
        <li>
          <StatCard
            label="Profile"
            icon={<UserIcon className="h-5 w-5" />}
            hue="vivid-violet"
            value={completeness.percent}
            format={(n) => `${Math.round(n)}%`}
            loading={profile.loading && !profile.data}
            caption={completeness.percent === 100 ? 'Complete' : `${completeness.completed} of ${completeness.total} steps done`}
            to="/account/profile"
          />
        </li>
      </ul>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <div className="space-y-6">
          <div style={stagger(2)}>
            <SectionCard
              id="recent-orders"
              title="Recent orders"
              icon={<PackageIcon className="h-5 w-5" />}
              action={
                <Link to="/account/orders" className={viewAllLink}>
                  View all
                  <ChevronRightIcon className="h-4 w-4 transition-transform duration-700 group-hover:translate-x-1" />
                </Link>
              }
            >
              {orders.loading && !orders.data ? (
                <CardSkeleton />
              ) : orders.error ? (
                <CardError message={errorMessage(orders.error)} onRetry={orders.reload} />
              ) : recentOrders.length === 0 ? (
                <div className="flex flex-col items-center gap-3 py-8 text-center">
                  <PackageIcon className="h-10 w-10 text-on-surface-muted" />
                  <p className="font-bold text-on-surface">No orders yet</p>
                  <p className="text-sm text-on-surface-muted">When you place an order it will show up here.</p>
                  <Link to="/" className={primaryLink}>
                    Start shopping
                  </Link>
                </div>
              ) : (
                <ul className="divide-y-2 divide-outline/20">
                  {recentOrders.map((order) => (
                    <li key={order.id}>
                      <Link
                        to={`/orders/${order.id}`}
                        className="group flex flex-wrap items-center gap-x-4 gap-y-2 rounded-2xl px-2 py-3 transition-colors duration-700 hover:bg-surface-variant"
                      >
                        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border-2 border-ink bg-vivid-sky/25 text-on-surface">
                          <PackageIcon className="h-5 w-5" />
                        </span>
                        <span className="min-w-[8rem] flex-1">
                          <span className="block font-mono text-sm font-bold text-on-surface">#{order.id.slice(0, 8)}</span>
                          <span className="block text-xs text-on-surface-muted">
                            {new Date(order.placedAt).toLocaleDateString(undefined, { dateStyle: 'medium' })} · {order.itemCount}{' '}
                            {order.itemCount === 1 ? 'item' : 'items'}
                          </span>
                        </span>
                        <StatusPill status={order.status} />
                        <span className="ml-auto min-w-[5.5rem] text-right font-bold text-on-surface sm:ml-0">
                          {formatMoney(Number(order.totalAmount), order.currency)}
                        </span>
                        <ChevronRightIcon className="hidden h-4 w-4 text-on-surface-muted transition-transform duration-700 group-hover:translate-x-1 sm:block" />
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          </div>

          <div style={stagger(3)}>
            <SectionCard
              id="wishlist"
              title="From your wishlist"
              icon={<HeartIcon className="h-5 w-5" />}
              action={
                wishlist.length > 0 ? (
                  <Link to="/wishlist" className={viewAllLink}>
                    View all {wishlist.length}
                    <ChevronRightIcon className="h-4 w-4 transition-transform duration-700 group-hover:translate-x-1" />
                  </Link>
                ) : undefined
              }
            >
              {wishlist.length === 0 ? (
                <div className="flex flex-col items-center gap-3 py-8 text-center">
                  <HeartIcon className="h-10 w-10 text-on-surface-muted" />
                  <p className="font-bold text-on-surface">Nothing saved yet</p>
                  <p className="text-sm text-on-surface-muted">Tap the heart on any product to keep it here.</p>
                  <Link to="/" className={secondaryLink}>
                    Explore products
                  </Link>
                </div>
              ) : (
                <ul className="grid grid-cols-3 gap-3 sm:grid-cols-4 lg:grid-cols-6">
                  {wishlist.slice(0, 6).map((item) => (
                    <li key={item.productId}>
                      <Link
                        to={`/products/${item.productId}`}
                        title={item.snapshot?.name}
                        className="group block space-y-1.5"
                      >
                        <span className="relative block aspect-square overflow-hidden rounded-2xl border-2 border-outline bg-surface-variant transition-all duration-700 group-hover:-translate-y-1 group-hover:shadow-card">
                          <ProductImage
                            src={item.snapshot?.imageUrl}
                            alt={item.snapshot?.name ?? 'Saved product'}
                            productId={item.productId}
                          />
                        </span>
                        {item.snapshot && (
                          <span className="block truncate text-xs font-bold text-on-surface">
                            {formatMoney(item.snapshot.price, item.snapshot.currency)}
                          </span>
                        )}
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          </div>
        </div>

        <div className="space-y-6">
          <div style={stagger(2)}>
            <SectionCard id="completeness" title="Profile strength" icon={<Starburst className="h-5 w-5" />}>
              {profile.loading && !profile.data ? (
                <CardSkeleton rows={2} />
              ) : (
                <div className="space-y-5">
                  <div className="flex items-center gap-5">
                    <CompletenessRing percent={completeness.percent} />
                    <p className="text-sm text-on-surface-muted">
                      {completeness.percent === 100
                        ? 'Your profile is complete. Nice work!'
                        : `${completeness.completed} of ${completeness.total} steps done. Finish the rest for faster checkout and delivery.`}
                    </p>
                  </div>
                  <ul className="space-y-2">
                    {completeness.steps.map((step) => {
                      const content = (
                        <>
                          <span
                            className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full border-2 border-ink transition-colors duration-700 ${
                              step.done ? 'bg-success text-white dark:text-ink' : 'bg-surface'
                            }`}
                            aria-hidden="true"
                          >
                            {step.done && <CheckIcon className="h-3.5 w-3.5" />}
                          </span>
                          <span className="min-w-0 flex-1">
                            <span className={`block text-sm font-bold ${step.done ? 'text-on-surface-muted' : 'text-on-surface'}`}>
                              {step.label}
                            </span>
                            {!step.done && <span className="block text-xs text-on-surface-muted">{step.hint}</span>}
                          </span>
                          {!step.done && (
                            <ArrowRightIcon className="h-4 w-4 text-primary transition-transform duration-700 group-hover:translate-x-1" />
                          )}
                        </>
                      )
                      return (
                        <li key={step.key}>
                          {step.done ? (
                            <div className="flex items-center gap-3 rounded-2xl px-2 py-1.5">{content}</div>
                          ) : (
                            <Link
                              to={step.to}
                              className="group flex items-center gap-3 rounded-2xl px-2 py-1.5 transition-colors duration-700 hover:bg-surface-variant"
                            >
                              {content}
                            </Link>
                          )}
                        </li>
                      )
                    })}
                  </ul>
                </div>
              )}
            </SectionCard>
          </div>

          <div style={stagger(3)}>
            <SectionCard
              id="default-address"
              title="Delivery address"
              icon={<MapPinIcon className="h-5 w-5" />}
              action={
                <Link to="/account/addresses" className={viewAllLink}>
                  Manage
                  <ChevronRightIcon className="h-4 w-4 transition-transform duration-700 group-hover:translate-x-1" />
                </Link>
              }
            >
              {addresses.loading && !addresses.data ? (
                <CardSkeleton rows={2} />
              ) : addresses.error ? (
                <CardError message={errorMessage(addresses.error)} onRetry={addresses.reload} />
              ) : !defaultAddress ? (
                <div className="space-y-3 py-2">
                  <p className="text-sm text-on-surface-muted">No saved address yet. Add one to speed up checkout.</p>
                  <Link to="/account/addresses" className={secondaryLink}>
                    Add an address
                  </Link>
                </div>
              ) : (
                <div className="flex gap-3">
                  <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border-2 border-ink bg-vivid-amber text-ink">
                    <MapPinIcon className="h-5 w-5" />
                  </span>
                  <address className="min-w-0 text-sm not-italic text-on-surface">
                    <span className="mb-0.5 flex items-center gap-2 font-bold">
                      {defaultAddress.label || 'Address'}
                      {defaultAddress.isDefault && (
                        <span className="rounded-full border-2 border-ink bg-vivid-amber px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-ink">
                          Default
                        </span>
                      )}
                    </span>
                    {formatAddressLines(defaultAddress).map((line, i) => (
                      <span key={i} className="block text-on-surface-muted">
                        {line}
                      </span>
                    ))}
                  </address>
                </div>
              )}
            </SectionCard>
          </div>

          <div style={stagger(4)} className="flex items-center gap-4 rounded-[1.75rem] border-2 border-dashed border-outline p-4">
            <Avatar name={profile.data?.displayName ?? user.displayName} email={user.email} size="md" />
            <div className="min-w-0 text-sm">
              <p className="truncate font-bold text-on-surface">{name}</p>
              <p className="truncate text-on-surface-muted">{profile.data?.phone || 'No phone number on file'}</p>
            </div>
            <Link to="/account/profile" className="ml-auto text-sm font-bold text-primary hover:underline">
              Edit
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}
