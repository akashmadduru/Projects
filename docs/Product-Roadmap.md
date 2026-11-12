# Product Roadmap: gap analysis vs Amazon / Flipkart

Owner's view of what the storefront is missing compared with Amazon and Flipkart, grouped into
phases and ranked by importance inside each phase. Phases continue the numbering in the root
[README](../README.md#roadmap) (Phases 1-5 are built).

**Working rules for these phases** (set 2026-11-12): build features as fast as possible; no test
cases and no HLD/LLD diagrams per feature; every shortcut goes into the
[Tech Debt Register](../techdebt/README.md) instead.

Size: **S** = under a day, **M** = a few days, **L** = a week or more.
"Touches" names the existing service that grows, or **new** for a new service.

---

## Where the product stands today

| Area | Have today | Biggest gap |
|---|---|---|
| Browse & search | Catalog, keyword search, category nav, 8 filters, 6 sorts, pagination | No autocomplete, no typo tolerance, no brand/attribute facets |
| Product page | Gallery + video, specs, seller card, stock badge, related products, buyer reviews with votes | No variants (size/colour), no Q&A, no delivery date check, no offers |
| Cart & checkout | Cart + wishlist, anonymous merge, address book, idempotent order placement | **No payment**, no shipping/tax, no coupons, stock never reserved |
| After purchase | Order list/detail, cancel | No status beyond PLACED/CANCELLED, no tracking, no returns/refunds, no invoice, no emails |
| Accounts | Email/password, Google SSO, profile, addresses | No password reset, no email/phone verification, no OTP login |
| Admin | Product CRUD, inventory, dashboard | No order management, no refunds, no customers view, no promotions |
| Retention | Wishlist | No notifications, loyalty, recommendations, alerts |

The single most important fact: **the store cannot take money or ship anything yet.** Phase 6 fixes
that; everything after it grows conversion and retention.

---

## Phase 6: Sell for real (must-have before any real customer)

| # | Feature | Why it matters | Today | Touches | Size |
|---|---|---|---|---|---|
| 1 | **Online payments** (cards, UPI, net banking, wallets) via Razorpay/Stripe; payment status on the order | No revenue without it | Order is placed with no payment | **new** payment-service, order-service, storefront checkout | L |
| 2 | **Cash on Delivery** option with COD limits | Large share of Flipkart orders; lowers first-purchase friction | — | payment-service, order-service | S |
| 3 | **Stock reservation and decrement** at checkout; release on cancel/payment failure | Prevents overselling (TD-BE-01) | Inventory is advisory only | inventory-service, order-service | M |
| 4 | **Order lifecycle**: PLACED → CONFIRMED → PACKED → SHIPPED → OUT_FOR_DELIVERY → DELIVERED, with a tracking timeline on the order page | Customers' #1 post-purchase question is "where is my order" | PLACED / CANCELLED only | order-service, storefront OrderPage | M |
| 5 | **Admin order management**: list/search orders, advance status, add tracking number, cancel, refund | Operations cannot fulfil without it | — | admin SPA, order-service | M |
| 6 | **Shipping charges and delivery-date estimate** by pincode (free above a threshold) | Price transparency; Amazon/Flipkart show "Delivery by Tue" everywhere | — | order-service (+ pincode table), product page, checkout | M |
| 7 | **Tax (GST) calculation and downloadable invoice PDF** | Legal requirement for selling in India; B2B buyers need invoices | — | order-service | M |
| 8 | **Order notifications** by email (and SMS hook): placed, shipped, delivered, cancelled, refunded | Trust; reduces support load | — | **new** notification-service (consumes `order.events`) | M |
| 9 | **Returns and refunds**: return window per product, return request with reason, pickup status, refund to source/wallet | Expected by every Amazon/Flipkart customer; drives purchase confidence | — | order-service, payment-service, admin | L |
| 10 | **Coupons / promo codes** at checkout (flat, %, min order, first order, per-user limits) | Primary acquisition lever | — | **new** promotion-service (or order-service module), checkout, admin | M |
| 11 | **Password reset, email verification, phone OTP login** | Account recovery; Flipkart is phone-first | Email/password + Google only | auth-service, storefront | M |
| 12 | **Buy Now** (skip cart) and **Save for later** in cart | Faster checkout; recovers removed items | Cart only | cart-service, storefront | S |

## Phase 7: Discovery and conversion

| # | Feature | Why it matters | Today | Touches | Size |
|---|---|---|---|---|---|
| 1 | **Search autocomplete and suggestions** (products, categories, recent searches) | Most sessions start in the search box | Plain keyword box | product-service, SearchBar | M |
| 2 | **Typo-tolerant, relevance-ranked search** (Postgres full-text + trigram first, OpenSearch later) | "iphnoe" must still find iPhone | Substring match | product-service | M |
| 3 | **Product variants** (size, colour, storage) with per-variant price, stock and images | Fashion/electronics are unsellable without it | One SKU per product | product-service, inventory-service, PDP, cart | L |
| 4 | **Brand and attribute facets** (brand, RAM, size, colour…) driven by category | Amazon's left rail; narrows big catalogues fast | 8 fixed filters | product-service, FilterPanel | M |
| 5 | **Pincode delivery check on the product page** ("Delivery by Fri, free") | Removes the biggest pre-cart doubt | — | product page (reuses Phase 6 #6) | S |
| 6 | **Offers on the product page**: bank offers, coupons that apply, EMI options | Flipkart's conversion driver | — | promotion-service, PDP | M |
| 7 | **Deals of the Day / lightning deals** with countdown and limited quantity | Urgency; homepage traffic magnet | Static promo banner | promotion-service, home page, admin | M |
| 8 | **Recently viewed** and **Customers also viewed / Frequently bought together** | Cross-sell; Amazon attributes a large share of revenue to recommendations | "Related products" only | **new** recommendation-service (start with co-purchase counts from orders) | M |
| 9 | **Customer Q&A** on product pages (ask, answer, upvote) | Answers pre-purchase questions reviews don't cover | — | rating-service (new module), PDP | M |
| 10 | **Review photos/videos** and review filters (by stars, verified, with media) | Social proof; more trusted than seller photos | Text reviews only | rating-service, media upload | M |
| 11 | **Price-drop and back-in-stock alerts** on wishlist items | Brings users back at buying moments | Wishlist without alerts | notification-service, cart-service | S |
| 12 | **Compare products** side by side (up to 4) | Key for electronics/appliances | — | storefront | S |
| 13 | **Image zoom, 360° and full-screen gallery** | Replaces touching the product | Basic gallery | storefront | S |
| 14 | **SEO and shareability**: clean URLs with slugs, breadcrumbs, meta/OG tags, sitemap, share button | Free organic traffic | SPA with id URLs | storefront (SSR/prerender), product-service | M |

## Phase 8: Retention and loyalty

| # | Feature | Why it matters | Today | Touches | Size |
|---|---|---|---|---|---|
| 1 | **Membership programme** (Prime / Flipkart Plus style): free/fast shipping, early deal access | Members buy several times more often | — | **new** membership module in user-service, order-service pricing | L |
| 2 | **Reward points / coins** earned per order, redeemable at checkout | Repeat-purchase habit | — | **new** loyalty-service, checkout | M |
| 3 | **Wallet and gift cards** (store credit, refunds to wallet, gift card purchase/redeem) | Faster refunds, gifting revenue | — | payment-service | M |
| 4 | **Abandoned cart reminders** (email/push after N hours) | Recovers a meaningful share of lost carts | — | notification-service, cart-service | S |
| 5 | **Order again / Buy it again** list | One-click repeat purchase for consumables | — | order-service, storefront | S |
| 6 | **Notification centre** in the app (order updates, offers, alerts) | Single place for every message | Toasts only | notification-service, Header | M |
| 7 | **Personalised home page** (recently viewed, recommended, deals for you) | First screen sells | Same catalog for everyone | recommendation-service, home | M |
| 8 | **Referral programme** (invite → both get credit) | Cheapest acquisition channel | — | loyalty-service, user-service | M |
| 9 | **Multiple and shareable lists** (gift lists, wedding registry, public wishlist link) | Gifting and social sharing | One private wishlist | cart-service | S |
| 10 | **Subscribe & Save** for repeat items (auto-reorder on a schedule, discount) | Predictable recurring revenue | — | order-service scheduler, payment-service | L |
| 11 | **Web push and email marketing campaigns** with opt-in preferences | Re-engagement at scale | — | notification-service, account settings | M |

## Phase 9: Marketplace and sellers

| # | Feature | Why it matters | Today | Touches | Size |
|---|---|---|---|---|---|
| 1 | **Seller onboarding with KYC** (GST, PAN, bank account verification) | Amazon/Flipkart are marketplaces; catalogue growth comes from sellers | Seller is just a name on a product | **new** seller-service | L |
| 2 | **Seller portal**: own listings, stock, prices, orders to fulfil | Sellers self-serve instead of the admin team | Admin-only catalogue | **new** seller app, product-service, order-service | L |
| 3 | **Multiple sellers per product** with a "buy box" winner (price, rating, delivery speed) | Price competition; core marketplace mechanic | One seller per product | product-service, order-service | L |
| 4 | **Split orders per seller / per warehouse** (sub-orders, separate shipments) | Required once there is more than one seller | One order = one shipment | order-service | M |
| 5 | **Commission, settlement and payouts** to sellers | Sellers must get paid | — | **new** settlement module in payment-service | L |
| 6 | **Seller ratings and feedback** shown on PDP and seller page | Trust in third-party sellers | Static seller rating field | rating-service | S |
| 7 | **Bulk catalogue upload** (CSV/Excel) and listing quality checks | Sellers list thousands of SKUs | One-by-one admin form | product-service, seller portal | M |
| 8 | **Sponsored products / ads** in search and PDP | Major profit line for both platforms | — | **new** ads module, search ranking | L |
| 9 | **Seller analytics dashboard** (sales, traffic, conversion, returns) | Keeps sellers investing | — | seller portal, reporting | M |
| 10 | **Warehouse / fulfilment network** (stock per warehouse, nearest-warehouse routing) | Fast delivery promises | Single stock number | inventory-service | L |

## Phase 10: Trust, support and scale

| # | Feature | Why it matters | Today | Touches | Size |
|---|---|---|---|---|---|
| 1 | **Customer support**: help centre, order-linked tickets, live chat / chatbot | Every order issue currently has no channel | — | **new** support-service, storefront, admin | L |
| 2 | **Two-factor authentication and login alerts** | Account takeover protection | — | auth-service | M |
| 3 | **Fraud and abuse checks** (COD abuse, return abuse, fake reviews, velocity limits) | Protects margin and review trust | — | order-service, rating-service | M |
| 4 | **Admin RBAC and audit log** (support, catalogue, finance roles) | Safe operations as the team grows | Coarse roles | auth-service, admin | M |
| 5 | **Privacy controls**: data export, account deletion, cookie consent | Legal requirement (DPDP Act, GDPR) | — | user-service + all services | M |
| 6 | **Multi-language and multi-currency** | Reach beyond English-speaking metros | English, single currency per order | storefront i18n, product-service | M |
| 7 | **Progressive Web App** (installable, offline catalogue, push) and mobile-first polish | Most traffic is mobile | Responsive web | storefront | M |
| 8 | **Image CDN and optimisation** (responsive sizes, WebP/AVIF, lazy loading) | Page speed = conversion | Raw image URLs | infra, storefront | S |
| 9 | **Accessibility audit (WCAG 2.2 AA)** across checkout and account | Legal and reach | Partial | storefront | M |
| 10 | **Observability and reliability**: tracing, dashboards, alerts, DLQ for Kafka, rate limiting on writes | Keeps the shop up during sales | Metrics endpoints only | all services, infra | M |
| 11 | **Exchange offers** (trade in old device for a discount) | Flipkart signature for electronics | — | order-service, promotion-service | L |

---

## Build order for "as fast as possible"

Start with Phase 6 in this order; items on the same line can be built in parallel:

1. **#3 stock reservation**, **#4 order lifecycle**, **#12 Buy Now / Save for later** (no new services)
2. **#1 payments** + **#2 COD** (new payment-service; checkout becomes "pay, then place")
3. **#5 admin order management** + **#6 shipping & delivery estimate** + **#7 GST invoice**
4. **#8 notifications** (needs `order.events`, which order-service does not publish yet; see TD-BE-04)
5. **#10 coupons** + **#11 password reset / OTP**
6. **#9 returns & refunds** (needs payments and the lifecycle in place)

Then take Phase 7 #1, #2, #3, #5 and #8 next. Those five do the most for conversion.

## How tech debt is tracked

All technical debt stays in README files under [`techdebt/`](../techdebt/README.md). The index there
has severity, effort and a stable ID for each item. Rules for the roadmap phases:

- **No tests and no HLD/LLD per feature.** Record the missing tests as a tech-debt item instead.
- **Every shortcut gets an ID** in the register in the same change that introduces it.
- **Some roadmap items close tech debt.** When one is built, remove the matching tech-debt items:

| Roadmap item | Closes |
|---|---|
| Phase 6 #1 payments, #6 shipping, #7 tax | TD-BE-02 |
| Phase 6 #3 stock reservation | TD-BE-01, TD-BE-05 |
| Phase 6 #8 notifications | TD-BE-04 (`order.events` publisher) |
| Phase 6 #11 password reset / verification | TD-SEC-04, TD-FE-05 |
| Phase 10 #10 observability (DLQ, retries) | TD-BE-03, TD-RT-02 |
