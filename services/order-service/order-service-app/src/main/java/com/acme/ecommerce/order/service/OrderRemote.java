package com.acme.ecommerce.order.service;

import com.acme.ecommerce.cart.api.dto.CartItemResponse;
import com.acme.ecommerce.cart.api.dto.ConsumedLinesRequest;
import com.acme.ecommerce.order.client.CartInternalFeignClient;
import com.acme.ecommerce.order.client.InventoryFeignClient;
import com.acme.ecommerce.order.client.InventoryFeignClient.Item;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.CreatePayment;
import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentResult;
import com.acme.ecommerce.order.client.PaymentFeignClient.RefundRequest;
import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.client.PromotionFeignClient.RedeemRequest;
import com.acme.ecommerce.order.client.PromotionFeignClient.ValidateRequest;
import com.acme.ecommerce.order.client.PromotionFeignClient.Validation;
import com.acme.ecommerce.order.client.RemoteCalls;
import com.acme.ecommerce.order.client.SellerInternalFeignClient;
import com.acme.ecommerce.order.client.UserInternalFeignClient;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.outbox.Outbox;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import feign.FeignException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls to inventory, payment and promotion. "Strict" methods translate failures into errors the
 * caller must handle. The "best-effort" compensations (stock restore, releases, payment cancel, COD
 * collected, return restock) are durable since TD-P6-01: they write an outbox row, in the caller's
 * transaction when there is one, and {@link com.acme.ecommerce.order.outbox.OutboxRelay} delivers
 * it through the matching "...Now" method with retries. The names are kept so callers did not
 * change.
 */
@Component
public class OrderRemote {

  private static final Logger log = LoggerFactory.getLogger(OrderRemote.class);

  private final InventoryFeignClient inventory;
  private final PaymentFeignClient payments;
  private final PromotionFeignClient promotions;
  private final UserInternalFeignClient usersInternal;
  private final SellerInternalFeignClient sellers;
  private final WalletCreditBacks walletCreditBacks;
  private final Outbox outbox;
  private final CartInternalFeignClient cartInternal;

  public OrderRemote(
      InventoryFeignClient inventory,
      PaymentFeignClient payments,
      PromotionFeignClient promotions,
      UserInternalFeignClient usersInternal,
      SellerInternalFeignClient sellers,
      WalletCreditBacks walletCreditBacks,
      Outbox outbox,
      CartInternalFeignClient cartInternal) {
    this.outbox = outbox;
    this.cartInternal = cartInternal;
    this.walletCreditBacks = walletCreditBacks;
    this.inventory = inventory;
    this.payments = payments;
    this.promotions = promotions;
    this.usersInternal = usersInternal;
    this.sellers = sellers;
  }

  // ---- sellers -----------------------------------------------------------------------------

  /**
   * The APPROVED seller account of a user, or null (not a seller / suspended). 503 if unreachable.
   */
  public java.util.UUID approvedSellerId(UUID userId) {
    java.time.Instant now = java.time.Instant.now();
    SellerLookup cached = sellerCache.get(userId);
    if (cached != null && cached.at().plus(SELLER_TTL).isAfter(now)) {
      return cached.sellerId();
    }
    UUID sellerId;
    try {
      SellerInternalFeignClient.SellerRef ref = sellers.byUser(userId);
      sellerId = ref != null && "APPROVED".equalsIgnoreCase(ref.status()) ? ref.sellerId() : null;
    } catch (FeignException.NotFound e) {
      sellerId = null;
    } catch (FeignException e) {
      // Stale-while-error: a recent answer beats a 503 for every seller shipment call.
      if (cached != null && cached.at().plus(SELLER_STALE_MAX).isAfter(now)) {
        return cached.sellerId();
      }
      throw RemoteCalls.unavailable("seller-service", e);
    }
    if (sellerCache.size() > SELLER_CACHE_MAX) {
      sellerCache.clear();
    }
    sellerCache.put(userId, new SellerLookup(sellerId, now));
    return sellerId;
  }

  /**
   * Seller lookups per user (TD-P9-11): every seller shipment call resolved the seller through
   * seller-service, twice on the PATCH path. Fresh for 60 s (a suspension takes effect within a
   * minute; the role is also revoked in auth-service), served stale for up to 10 min while
   * seller-service is down.
   */
  private record SellerLookup(UUID sellerId, java.time.Instant at) {}

  private static final java.time.Duration SELLER_TTL = java.time.Duration.ofSeconds(60);
  private static final java.time.Duration SELLER_STALE_MAX = java.time.Duration.ofMinutes(10);
  private static final int SELLER_CACHE_MAX = 10_000;
  private final java.util.concurrent.ConcurrentHashMap<UUID, SellerLookup> sellerCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  // ---- inventory ---------------------------------------------------------------------------

  /** One stock line to reserve; {@code offerId} null = the platform/default stock. */
  public record StockLine(UUID productId, UUID variantId, UUID offerId, int quantity) {}

  /**
   * Reserves stock and returns where it was reserved (empty with an inventory-service that still
   * answers 204: the caller then falls back to the platform warehouse).
   */
  public List<InventoryFeignClient.Allocation> reserveStock(
      UUID orderId, List<StockLine> lines, String pincode) {
    try {
      InventoryFeignClient.ReserveResult result =
          inventory.reserve(
              new InventoryFeignClient.ReserveRequest(
                  orderId,
                  lines.stream()
                      .map(l -> new Item(l.productId(), l.variantId(), l.quantity(), l.offerId()))
                      .toList(),
                  pincode));
      return result == null || result.allocations() == null ? List.of() : result.allocations();
    } catch (FeignException e) {
      throw RemoteCalls.translate("inventory-service", e);
    }
  }

  /** Durable: puts the order's stock back and releases its deal claims and exchange quote. */
  public void restoreStockBestEffort(UUID orderId) {
    outbox.enqueue(Kind.RESTORE_STOCK, orderId);
    outbox.enqueue(Kind.RELEASE_DEALS, orderId);
    outbox.enqueue(Kind.RELEASE_EXCHANGE, orderId);
  }

  /** One return's lines, as stored in the outbox payload. */
  public record ReleasePayload(UUID returnId, List<Item> items) {}

  /** Durable: restocks the lines of an approved return (once per return). */
  public void releaseStock(UUID orderId, UUID returnId, List<ReturnLine> lines) {
    outbox.enqueue(
        Kind.RELEASE_STOCK,
        orderId,
        new ReleasePayload(
            returnId,
            lines.stream()
                .map(l -> new Item(l.productId(), l.variantId(), l.quantity(), l.offerId()))
                .toList()),
        "RELEASE_STOCK:" + orderId + ":" + returnId);
  }

  // ---- cart clear (durable, scoped to the lines the order consumed) --------------------------

  /** What the relay needs to clear one order's lines from its user's cart. */
  public record ClearCartPayload(
      UUID userId, UUID orderId, List<ConsumedLinesRequest.Line> lines) {}

  static String clearCartKey(UUID orderId) {
    return Kind.CLEAR_CART + ":" + orderId;
  }

  /**
   * Records the clear (idempotent per order, in the caller's transaction when there is one). The
   * aggregate is per user so a pending clear never holds back an order's own effects.
   */
  public void enqueueClearCart(UUID userId, UUID orderId, List<CartItemResponse> items) {
    outbox.enqueue(
        Kind.CLEAR_CART,
        UUID.nameUUIDFromBytes(
            ("cart:" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        new ClearCartPayload(userId, orderId, toLines(items)),
        clearCartKey(orderId));
  }

  private static List<ConsumedLinesRequest.Line> toLines(List<CartItemResponse> items) {
    return items.stream()
        .map(
            i ->
                new ConsumedLinesRequest.Line(
                    i.productId(), i.variantId(), i.offerId(), i.quantity(), i.addedAt()))
        .toList();
  }

  /** One inline try at a recorded clear: DONE on success, left PENDING for the relay on failure. */
  public void attemptClearCart(UUID userId, UUID orderId, List<CartItemResponse> items) {
    try {
      clearCartNow(new ClearCartPayload(userId, orderId, toLines(items)));
      outbox.complete(clearCartKey(orderId));
    } catch (RuntimeException e) {
      log.warn("Order {} placed; its cart clear is queued for retry: {}", orderId, e.toString());
    }
  }

  /** Durable: removes the order's lines from the user's cart, now and then until it lands. */
  public void clearCart(UUID userId, UUID orderId, List<CartItemResponse> items) {
    try {
      enqueueClearCart(userId, orderId, items);
    } catch (RuntimeException e) {
      // The order already exists: never fail it. The fingerprint guard still blocks a re-order.
      log.warn(
          "Order {} placed but its cart clear could not be recorded: {}", orderId, e.toString());
      return;
    }
    attemptClearCart(userId, orderId, items);
  }

  // ---- strict calls the outbox relay delivers (throw on failure so the relay retries) ----------

  public void clearCartNow(ClearCartPayload payload) {
    cartInternal.consumed(
        payload.userId(), new ConsumedLinesRequest(payload.orderId(), payload.lines()));
  }

  public void restoreStockNow(UUID orderId) {
    inventory.restore(orderId);
  }

  public void releaseDealsNow(UUID orderId) {
    promotions.releaseDeals(orderId);
  }

  public void releaseExchangeNow(UUID orderId) {
    promotions.releaseExchange(orderId);
  }

  public void releaseStockNow(UUID orderId, ReleasePayload payload) {
    inventory.release(
        orderId, new InventoryFeignClient.ReleaseRequest(payload.returnId(), payload.items()));
  }

  public void releaseCouponNow(UUID orderId) {
    promotions.release(orderId);
  }

  public void releaseCoinsNow(UUID orderId) {
    promotions.releaseCoins(orderId);
  }

  public void cancelPaymentNow(UUID orderId) {
    payments.cancelUnpaid(orderId);
  }

  public void codCollectedNow(UUID orderId) {
    payments.codCollected(orderId);
  }

  // ---- promotion ---------------------------------------------------------------------------

  public Validation validateCoupon(
      String code, BigDecimal subtotal, String currency, boolean firstOrder) {
    try {
      return promotions.validate(new ValidateRequest(code, subtotal, currency, firstOrder));
    } catch (FeignException e) {
      throw RemoteCalls.translate("promotion-service", e);
    }
  }

  public void redeemCoupon(
      String code,
      UUID userId,
      UUID orderId,
      BigDecimal subtotal,
      String currency,
      boolean firstOrder) {
    try {
      promotions.redeem(new RedeemRequest(code, userId, orderId, subtotal, currency, firstOrder));
    } catch (FeignException e) {
      throw RemoteCalls.translate("promotion-service", e);
    }
  }

  /** Durable: releases the order's coupon redemption (a no-op if there was none). */
  public void releaseCouponBestEffort(UUID orderId) {
    outbox.enqueue(Kind.RELEASE_COUPON, orderId);
  }

  /** Active deal prices for the lines; empty on any failure (deals are best-effort at pricing). */
  public List<PromotionFeignClient.DealPrice> dealPrices(
      UUID userId, List<PromotionFeignClient.DealKey> keys) {
    try {
      PromotionFeignClient.DealPricesResponse r =
          promotions.dealPrices(new PromotionFeignClient.DealPricesRequest(keys, userId));
      return r == null || r.prices() == null ? List.of() : r.prices();
    } catch (RuntimeException e) {
      log.warn("Deal prices unavailable, using list prices: {}", e.toString());
      return List.of();
    }
  }

  public PromotionFeignClient.ExchangeQuoteView exchangeQuote(UUID quoteId, UUID userId) {
    try {
      return promotions.exchangeQuote(quoteId, userId);
    } catch (FeignException e) {
      throw RemoteCalls.translate("promotion-service", e);
    }
  }

  public void applyExchange(UUID quoteId, UUID userId, UUID orderId) {
    try {
      promotions.applyExchange(
          new PromotionFeignClient.ExchangeApplyRequest(quoteId, userId, orderId));
    } catch (FeignException e) {
      throw RemoteCalls.translate("promotion-service", e);
    }
  }

  /** Durable: releases the order's exchange quote (a no-op if there was none). */
  public void releaseExchangeBestEffort(UUID orderId) {
    outbox.enqueue(Kind.RELEASE_EXCHANGE, orderId);
  }

  /** Strict wallet debit with a caller-chosen idempotency key (exchange shortfall). */
  public void debitWalletKeyed(
      UUID userId, BigDecimal amount, String currency, UUID orderId, String key) {
    try {
      payments.walletDebit(
          new PaymentFeignClient.WalletDebit(userId, amount, currency, orderId, key));
    } catch (FeignException e) {
      throw RemoteCalls.translate("payment-service", e);
    }
  }

  /** Strict: the order was priced with a deal, so a sold-out claim must fail placement. */
  public void claimDeals(UUID orderId, List<PromotionFeignClient.DealClaimItem> items) {
    try {
      promotions.claimDeals(new PromotionFeignClient.DealClaimsRequest(orderId, items));
    } catch (FeignException e) {
      throw RemoteCalls.translate("promotion-service", e);
    }
  }

  // ---- payment -----------------------------------------------------------------------------

  /** Strict: used for CARD, where there is no order without a payment. */
  public PaymentResult createPayment(
      UUID orderId, UUID userId, BigDecimal amount, String currency, String method) {
    try {
      return payments.create(new CreatePayment(orderId, userId, amount, currency, method));
    } catch (FeignException e) {
      throw RemoteCalls.translate("payment-service", e);
    }
  }

  /** Strict: money movement must not be silently skipped. 503 when payment-service is down. */
  public void refund(UUID orderId, BigDecimal amount, String reason, String idempotencyKey) {
    refund(orderId, amount, reason, idempotencyKey, null);
  }

  /**
   * Strict refund; destination SOURCE (default) or WALLET. Returns the status of the refund row for
   * this key: PENDING, SUCCEEDED or FAILED. A null from an older payment-service is read as
   * SUCCEEDED for a wallet destination and as PENDING for a refund to the card (deploy
   * payment-service first).
   */
  public String refund(
      UUID orderId, BigDecimal amount, String reason, String idempotencyKey, String destination) {
    try {
      var result =
          payments.refund(orderId, new RefundRequest(amount, reason, idempotencyKey, destination));
      if (result != null && result.refundStatus() != null) {
        return result.refundStatus();
      }
      log.warn("payment-service sent no refundStatus for order {}", orderId);
      return "WALLET".equals(destination) ? "SUCCEEDED" : "PENDING";
    } catch (FeignException e) {
      throw RemoteCalls.translate("payment-service", e);
    }
  }

  /**
   * Durable: cancels the open payment of an order cancelled before it was paid. Until the relay
   * delivers it, a customer who still pays is refunded by the late-payment path in {@code
   * OrderLifecycleService#paymentResult}.
   */
  public void cancelPaymentBestEffort(UUID orderId) {
    outbox.enqueue(Kind.CANCEL_PAYMENT, orderId);
  }

  /** A wallet debit failed ambiguously: give it back if it happened (durable, TD-P8-23). */
  public void reverseWalletDebitDurable(
      UUID userId, BigDecimal amount, String currency, UUID orderId) {
    walletCreditBacks.reverseDebit(userId, amount, currency, orderId);
  }

  /** Durable: tells payment-service the cash was collected on delivery. */
  public void codCollectedBestEffort(UUID orderId) {
    outbox.enqueue(Kind.COD_COLLECTED, orderId);
  }

  // ---- wallet, coins, membership -------------------------------------------------------------

  /** Membership lookup; any failure means "not a member". */
  public boolean isMember(UUID userId) {
    try {
      UserInternalFeignClient.Membership m = usersInternal.membership(userId);
      return m != null && m.active();
    } catch (RuntimeException e) {
      log.debug("Membership lookup failed for {}: {}", userId, e.toString());
      return false;
    }
  }

  /** Address of a user without a bearer token (subscription job). */
  public AddressResponse addressFor(UUID userId, UUID addressId) {
    try {
      return usersInternal.address(userId, addressId);
    } catch (FeignException e) {
      throw RemoteCalls.translate("user-service", e);
    }
  }

  /** Wallet balance in the wallet currency; null when unknown (treated as nothing to spend). */
  public PaymentFeignClient.WalletBalance walletBalance(UUID userId) {
    try {
      return payments.walletBalance(userId);
    } catch (RuntimeException e) {
      log.warn("Wallet balance unavailable for {}: {}", userId, e.toString());
      return null;
    }
  }

  /** Strict, idempotent by key; 409 INSUFFICIENT_BALANCE surfaces to the caller. */
  public void debitWallet(UUID userId, BigDecimal amount, String currency, UUID orderId) {
    try {
      payments.walletDebit(
          new PaymentFeignClient.WalletDebit(
              userId, amount, currency, orderId, "order-debit-" + orderId));
    } catch (FeignException e) {
      throw RemoteCalls.translate("payment-service", e);
    }
  }

  /**
   * Gives back the wallet amount an order took. Durable (TD-P8-07): recorded before the call and
   * retried by {@link WalletCreditBacks} until it lands, so an outage no longer loses the money.
   */
  public void creditWalletBackBestEffort(
      UUID userId, BigDecimal amount, String currency, UUID orderId) {
    walletCreditBacks.creditBack(userId, amount, currency, orderId);
  }

  /** Strict credit used by return refunds (idempotent by key). */
  public void creditWallet(
      UUID userId, BigDecimal amount, String currency, UUID orderId, String key) {
    try {
      payments.walletCredit(
          new PaymentFeignClient.WalletCredit(
              userId, amount, currency, "REFUND", orderId.toString(), key));
    } catch (FeignException e) {
      throw RemoteCalls.translate("payment-service", e);
    }
  }

  /** Strict: 409 INSUFFICIENT_BALANCE when the user has too few coins. */
  public void redeemCoins(UUID userId, int coins, UUID orderId) {
    try {
      promotions.redeemCoins(new PromotionFeignClient.CoinRedeem(userId, coins, orderId));
    } catch (FeignException e) {
      throw RemoteCalls.translate("promotion-service", e);
    }
  }

  /** Durable: gives back coins the order redeemed (a no-op if there were none). */
  public void releaseCoinsBestEffort(UUID orderId) {
    outbox.enqueue(Kind.RELEASE_COINS, orderId);
  }
}
