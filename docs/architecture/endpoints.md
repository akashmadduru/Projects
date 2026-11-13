# Endpoint inventory

Every HTTP endpoint declared by a Spring controller, generated from the code on 2026-11-13
(309 endpoints in 12 services). Regenerate it after adding a controller.
Request and response shapes for Phases 1–5 are in [api.md](api.md); later phases are described in [phases/](../phases/README.md).

- `/api/**`: public through the gateway (the gateway and each service still enforce auth).
- `/api/admin/**`, `/api/seller/**`: role-gated (ADMIN, SUPPORT, FINANCE, CATALOG_MANAGER, SELLER).
- `/internal/**`: service-to-service only, guarded by `X-Internal-Token`. The gateway does not route them.

## auth-service (33)

| Method | Path | Controller |
|---|---|---|
| GET | `/.well-known/jwks.json` | JwksController |
| POST | `/api/auth/2fa/disable` | TwoFactorController |
| POST | `/api/auth/2fa/enable` | TwoFactorController |
| POST | `/api/auth/2fa/setup` | TwoFactorController |
| GET | `/api/auth/2fa/status` | TwoFactorController |
| POST | `/api/auth/2fa/verify` | TwoFactorController |
| POST | `/api/auth/email/verify` | AuthController |
| POST | `/api/auth/email/verify/resend` | AuthController |
| POST | `/api/auth/login` | AuthController |
| POST | `/api/auth/logout` | AuthController |
| GET | `/api/auth/me` | AuthController |
| POST | `/api/auth/me/phone` | AuthController |
| POST | `/api/auth/me/phone/verify` | AuthController |
| POST | `/api/auth/otp/request` | AuthController |
| POST | `/api/auth/otp/verify` | AuthController |
| POST | `/api/auth/password/forgot` | AuthController |
| POST | `/api/auth/password/reset` | AuthController |
| POST | `/api/auth/refresh` | AuthController |
| POST | `/api/auth/register` | AuthController |
| GET | `/api/auth/sessions` | TwoFactorController |
| DELETE | `/api/auth/sessions/{familyId}` | TwoFactorController |
| POST | `/api/auth/sessions/revoke-others` | TwoFactorController |
| POST | `/api/auth/validate` | AuthController |
| GET | `/api/admin/users` | AdminUserController |
| GET | `/api/admin/users/{id}` | AdminUserController |
| POST | `/api/admin/users/{id}/disable` | AdminUserController |
| POST | `/api/admin/users/{id}/enable` | AdminUserController |
| PUT | `/api/admin/users/{id}/roles` | AdminUserController |
| PUT | `/api/admin/users/{id}/status` | AdminUserController |
| GET | `/api/admin/users/stats` | AdminUserController |
| GET | `/internal/privacy/users/{userId}/export` | PrivacyExportController |
| POST | `/internal/privacy/users/{userId}/verify-password` | PrivacyVerifyController |
| POST | `/internal/users/{userId}/roles` | InternalRoleController |

## cart-service (28)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/alerts` | AlertController |
| POST | `/api/alerts` | AlertController |
| DELETE | `/api/alerts/{id}` | AlertController |
| DELETE | `/api/cart` | CartController |
| GET | `/api/cart` | CartController |
| POST | `/api/cart/items` | CartController |
| DELETE | `/api/cart/items/{productId}` | CartController |
| PUT | `/api/cart/items/{productId}` | CartController |
| POST | `/api/cart/items/{productId}/save-for-later` | CartController |
| POST | `/api/cart/merge` | CartController |
| DELETE | `/api/cart/saved/{productId}` | CartController |
| POST | `/api/cart/saved/{productId}/move-to-cart` | CartController |
| GET | `/api/lists` | ListController |
| POST | `/api/lists` | ListController |
| DELETE | `/api/lists/{id}` | ListController |
| GET | `/api/lists/{id}` | ListController |
| PUT | `/api/lists/{id}` | ListController |
| POST | `/api/lists/{id}/items` | ListController |
| DELETE | `/api/lists/{id}/items/{productId}` | ListController |
| DELETE | `/api/lists/{id}/share` | ListController |
| POST | `/api/lists/{id}/share` | ListController |
| GET | `/api/lists/shared/{token}` | ListController |
| GET | `/api/wishlist` | WishlistController |
| DELETE | `/api/wishlist/{productId}` | WishlistController |
| PUT | `/api/wishlist/{productId}` | WishlistController |
| POST | `/api/wishlist/merge` | WishlistController |
| POST | `/internal/cart/reminders/run` | ReminderController |
| GET | `/internal/privacy/users/{userId}/export` | PrivacyExportController |

## inventory-service (23)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/inventory` | InventoryController |
| POST | `/api/inventory` | InventoryController |
| DELETE | `/api/inventory/{id}` | InventoryController |
| GET | `/api/inventory/{id}` | InventoryController |
| PUT | `/api/inventory/{id}` | InventoryController |
| GET | `/api/inventory/by-product/{productId}` | InventoryController |
| GET | `/api/inventory/variants` | InventoryController |
| PUT | `/api/inventory/variants/{variantId}` | InventoryController |
| PUT | `/api/admin/stock` | WarehouseController |
| GET | `/api/admin/warehouses` | WarehouseController |
| POST | `/api/admin/warehouses` | WarehouseController |
| PUT | `/api/admin/warehouses/{id}` | WarehouseController |
| GET | `/api/seller/stock` | WarehouseController |
| PUT | `/api/seller/stock` | WarehouseController |
| GET | `/api/seller/warehouses` | WarehouseController |
| POST | `/api/seller/warehouses` | WarehouseController |
| POST | `/internal/inventory/reservations` | InternalReservationController |
| DELETE | `/internal/inventory/reservations/{orderId}` | InternalReservationController |
| GET | `/internal/inventory/reservations/{orderId}` | InternalReservationController |
| POST | `/internal/inventory/reservations/{orderId}/release` | InternalReservationController |
| PUT | `/internal/inventory/stock` | InternalStockController |
| GET | `/internal/inventory/stock/by-product/{productId}` | InternalStockController |
| GET | `/internal/inventory/variants` | InternalVariantStockController |

## notification-service (14)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/notifications` | InboxController |
| POST | `/api/notifications/{id}/read` | InboxController |
| POST | `/api/notifications/read-all` | InboxController |
| DELETE | `/api/push/subscriptions` | PushController |
| POST | `/api/push/subscriptions` | PushController |
| GET | `/api/push/vapid-public-key` | PushController |
| GET | `/api/admin/campaigns` | CampaignController |
| POST | `/api/admin/campaigns` | CampaignController |
| GET | `/api/admin/campaigns/{id}` | CampaignController |
| POST | `/api/admin/campaigns/{id}/send` | CampaignController |
| POST | `/internal/notifications/email` | InternalNotificationController |
| POST | `/internal/notifications/inbox` | InternalNotificationController |
| POST | `/internal/notifications/sms` | InternalNotificationController |
| GET | `/internal/privacy/users/{userId}/export` | PrivacyExportController |

## order-service (38)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/orders` | OrderController |
| POST | `/api/orders` | OrderController |
| GET | `/api/orders/{id}` | OrderController |
| POST | `/api/orders/{id}/cancel` | OrderController |
| GET | `/api/orders/{id}/invoice.pdf` | OrderController |
| GET | `/api/orders/{id}/returns` | OrderController |
| POST | `/api/orders/{id}/returns` | OrderController |
| GET | `/api/orders/{id}/shipments/{shipmentId}/invoice.pdf` | OrderController |
| GET | `/api/orders/buy-again` | BuyAgainController |
| GET | `/api/orders/delivery-estimate` | OrderController |
| GET | `/api/orders/purchases/{productId}` | OrderController |
| GET | `/api/recommendations/products/{id}/bought-together` | RecommendationController |
| GET | `/api/subscriptions` | SubscriptionController |
| POST | `/api/subscriptions` | SubscriptionController |
| PUT | `/api/subscriptions/{id}` | SubscriptionController |
| POST | `/api/subscriptions/{id}/skip` | SubscriptionController |
| POST | `/api/admin/exchange/{quoteId}/verify` | AdminExchangeController |
| GET | `/api/admin/orders` | AdminOrderController |
| GET | `/api/admin/orders/{id}` | AdminOrderController |
| POST | `/api/admin/orders/{id}/cancel` | AdminOrderController |
| POST | `/api/admin/orders/{id}/release-hold` | AdminOrderController |
| POST | `/api/admin/orders/{id}/shipments/{shipmentId}/status` | AdminOrderController |
| POST | `/api/admin/orders/{id}/status` | AdminOrderController |
| GET | `/api/admin/orders/stats` | AdminOrderController |
| GET | `/api/admin/returns` | AdminOrderController |
| POST | `/api/admin/returns/{id}/decision` | AdminOrderController |
| POST | `/api/admin/returns/{id}/status` | AdminOrderController |
| GET | `/api/seller/shipments` | SellerShipmentController |
| GET | `/api/seller/shipments/{id}` | SellerShipmentController |
| POST | `/api/seller/shipments/{id}/status` | SellerShipmentController |
| GET | `/internal/orders/{id}/brief` | InternalOrderLookupController |
| POST | `/internal/orders/{id}/payment-result` | InternalOrderController |
| GET | `/internal/orders/by-user/{userId}` | InternalOrderLookupController |
| GET | `/internal/orders/last-order-dates` | InternalSubscriptionController |
| GET | `/internal/orders/shipments/{id}` | InternalOrderController |
| GET | `/internal/privacy/users/{userId}/deletion-blockers` | PrivacyBlockersController |
| GET | `/internal/privacy/users/{userId}/export` | PrivacyExportController |
| POST | `/internal/subscriptions/run-due` | InternalSubscriptionController |

## payment-service (16)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/gift-cards` | WalletController |
| POST | `/api/gift-cards` | WalletController |
| POST | `/api/gift-cards/redeem` | WalletController |
| POST | `/api/payments/{paymentId}/simulate` | PaymentController |
| GET | `/api/payments/config` | PaymentController |
| GET | `/api/payments/orders/{orderId}` | PaymentController |
| POST | `/api/payments/webhook` | PaymentController |
| GET | `/api/wallet` | WalletController |
| GET | `/api/admin/payments` | AdminPaymentController |
| GET | `/api/admin/payments/{id}` | AdminPaymentController |
| GET | `/api/admin/payments/stats` | AdminPaymentController |
| GET | `/internal/privacy/users/{userId}/deletion-blockers` | PrivacyBlockersController |
| GET | `/internal/privacy/users/{userId}/export` | PrivacyExportController |
| GET | `/internal/wallet/{userId}` | InternalWalletController |
| POST | `/internal/wallet/credit` | InternalWalletController |
| POST | `/internal/wallet/debit` | InternalWalletController |

## product-service (30)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/categories` | CategoryController |
| POST | `/api/categories` | CategoryController |
| DELETE | `/api/categories/{id}` | CategoryController |
| GET | `/api/categories/{id}` | CategoryController |
| PUT | `/api/categories/{id}` | CategoryController |
| GET | `/api/products` | ProductController |
| POST | `/api/products` | ProductController |
| DELETE | `/api/products/{id}` | ProductController |
| GET | `/api/products/{id}` | ProductController |
| PUT | `/api/products/{id}` | ProductController |
| GET | `/api/products/{id}/also-viewed` | ProductController |
| PUT | `/api/products/{id}/variants` | ProductController |
| POST | `/api/products/{id}/views` | ProductController |
| GET | `/api/products/batch` | ProductController |
| GET | `/api/products/catalog/products/{id}` | ProductController |
| GET | `/api/products/facets` | ProductController |
| GET | `/api/products/fx-rates` | FxRateController |
| GET | `/api/products/slug/{slug}` | ProductController |
| GET | `/api/products/suggest` | ProductController |
| GET | `/sitemap.xml` | SitemapController |
| PUT | `/api/admin/fx-rates` | FxRateController |
| GET | `/api/seller/offers` | SellerController |
| POST | `/api/seller/offers` | SellerController |
| PUT | `/api/seller/offers/{id}` | SellerController |
| POST | `/api/seller/products` | SellerController |
| POST | `/api/seller/products/bulk` | SellerController |
| GET | `/api/seller/products/bulk/template.csv` | SellerController |
| GET | `/internal/offers/{id}` | InternalCatalogController |
| POST | `/internal/products/{id}/sync-prices` | InternalCatalogController |
| GET | `/internal/products/views/by-seller/{sellerId}` | InternalCatalogController |

## promotion-service (30)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/coins` | CoinController |
| GET | `/api/promotions/coupons/available` | PromotionController |
| POST | `/api/promotions/coupons/validate` | PromotionController |
| GET | `/api/promotions/deals/active` | OfferController |
| GET | `/api/promotions/exchange/programs` | ExchangeController |
| POST | `/api/promotions/exchange/quote` | ExchangeController |
| GET | `/api/promotions/exchange/quotes` | ExchangeController |
| GET | `/api/promotions/offers` | OfferController |
| GET | `/api/admin/coupons` | AdminCouponController |
| POST | `/api/admin/coupons` | AdminCouponController |
| PUT | `/api/admin/coupons/{id}` | AdminCouponController |
| POST | `/api/admin/coupons/{id}/deactivate` | AdminCouponController |
| GET | `/api/admin/deals` | AdminDealController |
| POST | `/api/admin/deals` | AdminDealController |
| PUT | `/api/admin/deals/{id}` | AdminDealController |
| POST | `/api/admin/deals/{id}/deactivate` | AdminDealController |
| GET | `/internal/coins/{userId}` | InternalCoinController |
| POST | `/internal/coins/redeem` | InternalCoinController |
| DELETE | `/internal/coins/redeem/{orderId}` | InternalCoinController |
| GET | `/internal/privacy/users/{userId}/export` | PrivacyExportController |
| POST | `/internal/promotions/deals/claims` | InternalDealController |
| DELETE | `/internal/promotions/deals/claims/{orderId}` | InternalDealController |
| POST | `/internal/promotions/deals/prices` | InternalDealController |
| POST | `/internal/promotions/exchange/{quoteId}/verify` | InternalExchangeController |
| POST | `/internal/promotions/exchange/apply` | InternalExchangeController |
| DELETE | `/internal/promotions/exchange/apply/{orderId}` | InternalExchangeController |
| GET | `/internal/promotions/exchange/quotes` | InternalExchangeController |
| GET | `/internal/promotions/exchange/quotes/{id}` | InternalExchangeController |
| POST | `/internal/promotions/redemptions` | InternalPromotionController |
| DELETE | `/internal/promotions/redemptions/{orderId}` | InternalPromotionController |

## rating-service (19)

| Method | Path | Controller |
|---|---|---|
| DELETE | `/api/ratings/{id}` | RatingController |
| GET | `/api/ratings/{id}` | RatingController |
| PUT | `/api/ratings/{id}` | RatingController |
| POST | `/api/ratings/{ratingId}/media` | RatingController |
| DELETE | `/api/ratings/{ratingId}/media/{mediaId}` | RatingController |
| POST | `/api/ratings/{ratingId}/votes/{voteType}` | RatingController |
| POST | `/api/ratings/answers/{answerId}/upvote` | RatingController |
| GET | `/api/ratings/media/{mediaId}` | RatingController |
| GET | `/api/ratings/products/{productId}` | RatingController |
| POST | `/api/ratings/products/{productId}` | RatingController |
| GET | `/api/ratings/products/{productId}/me` | RatingController |
| GET | `/api/ratings/products/{productId}/questions` | RatingController |
| POST | `/api/ratings/products/{productId}/questions` | RatingController |
| GET | `/api/ratings/products/{productId}/summary` | RatingController |
| GET | `/api/ratings/questions/{questionId}/answers` | RatingController |
| POST | `/api/ratings/questions/{questionId}/answers` | RatingController |
| POST | `/api/ratings/questions/{questionId}/upvote` | RatingController |
| GET | `/api/admin/ratings` | AdminRatingController |
| GET | `/internal/privacy/users/{userId}/export` | PrivacyExportController |

## seller-service (25)

| Method | Path | Controller |
|---|---|---|
| POST | `/api/ads/{adId}/click` | AdController |
| POST | `/api/ads/impressions` | AdController |
| GET | `/api/admin/payouts` | FinanceController |
| POST | `/api/admin/payouts/run` | FinanceController |
| GET | `/api/admin/sellers` | SellerController |
| GET | `/api/admin/sellers/{id}` | SellerController |
| POST | `/api/admin/sellers/{id}/decision` | SellerController |
| POST | `/api/admin/sellers/{id}/suspend` | SellerController |
| GET | `/api/seller/ads` | AdController |
| POST | `/api/seller/ads` | AdController |
| PUT | `/api/seller/ads/{id}` | AdController |
| GET | `/api/seller/analytics` | AnalyticsController |
| GET | `/api/seller/feedback` | FeedbackController |
| GET | `/api/seller/ledger` | FinanceController |
| GET | `/api/seller/payouts` | FinanceController |
| GET | `/api/sellers/{id}/feedback` | FeedbackController |
| POST | `/api/sellers/{id}/feedback` | FeedbackController |
| GET | `/api/sellers/{slug}` | SellerController |
| POST | `/api/sellers/apply` | SellerController |
| GET | `/api/sellers/me` | SellerController |
| GET | `/internal/ads/active` | AdController |
| GET | `/internal/privacy/users/{userId}/export` | PrivacyExportController |
| GET | `/internal/sellers` | SellerController |
| GET | `/internal/sellers/{id}` | SellerController |
| GET | `/internal/sellers/by-user/{userId}` | SellerController |

## support-service (21)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/support/articles` | ArticleController |
| GET | `/api/support/articles/{slug}` | ArticleController |
| POST | `/api/support/articles/{slug}/feedback` | ArticleController |
| POST | `/api/support/chat` | ChatController |
| GET | `/api/support/tickets` | TicketController |
| POST | `/api/support/tickets` | TicketController |
| GET | `/api/support/tickets/{id}` | TicketController |
| POST | `/api/support/tickets/{id}/close` | TicketController |
| POST | `/api/support/tickets/{id}/csat` | TicketController |
| POST | `/api/support/tickets/{id}/messages` | TicketController |
| GET | `/api/admin/audit` | AuditController |
| GET | `/api/admin/support/articles` | ArticleController |
| POST | `/api/admin/support/articles` | ArticleController |
| DELETE | `/api/admin/support/articles/{id}` | ArticleController |
| PUT | `/api/admin/support/articles/{id}` | ArticleController |
| GET | `/api/admin/support/tickets` | TicketController |
| GET | `/api/admin/support/tickets/{id}` | TicketController |
| POST | `/api/admin/support/tickets/{id}/assign` | TicketController |
| POST | `/api/admin/support/tickets/{id}/messages` | TicketController |
| POST | `/api/admin/support/tickets/{id}/status` | TicketController |
| GET | `/internal/privacy/users/{userId}/export` | PrivacyExportController |

## user-service (32)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/membership` | EngageController |
| POST | `/api/membership` | EngageController |
| POST | `/api/membership/cancel` | EngageController |
| GET | `/api/referrals` | EngageController |
| POST | `/api/referrals/claim` | EngageController |
| GET | `/api/users/me` | UserController |
| PUT | `/api/users/me` | UserController |
| GET | `/api/users/me/addresses` | UserController |
| POST | `/api/users/me/addresses` | UserController |
| DELETE | `/api/users/me/addresses/{id}` | UserController |
| GET | `/api/users/me/addresses/{id}` | UserController |
| PUT | `/api/users/me/addresses/{id}` | UserController |
| PUT | `/api/users/me/addresses/{id}/default` | UserController |
| GET | `/api/users/me/delete` | PrivacyController |
| POST | `/api/users/me/delete` | PrivacyController |
| POST | `/api/users/me/delete/cancel` | PrivacyController |
| POST | `/api/users/me/delete/code` | PrivacyController |
| POST | `/api/users/me/export` | PrivacyController |
| GET | `/api/users/me/export/{jobId}` | PrivacyController |
| GET | `/api/users/me/export/{jobId}/download` | PrivacyController |
| GET | `/api/users/me/preferences` | EngageController |
| PUT | `/api/users/me/preferences` | EngageController |
| GET | `/api/users/me/recently-viewed` | RecentlyViewedController |
| POST | `/api/users/me/recently-viewed` | RecentlyViewedController |
| POST | `/internal/privacy/deletions/run-due` | PrivacyController |
| GET | `/internal/referrals/by-referee/{userId}` | InternalEngageController |
| POST | `/internal/referrals/by-referee/{userId}/rewarded` | InternalEngageController |
| GET | `/internal/users` | InternalEngageController |
| GET | `/internal/users/{userId}/addresses/{id}` | InternalEngageController |
| GET | `/internal/users/{userId}/membership` | InternalEngageController |
| GET | `/internal/users/{userId}/preferences` | InternalEngageController |
| POST | `/internal/users/membership/payment-result` | InternalEngageController |
