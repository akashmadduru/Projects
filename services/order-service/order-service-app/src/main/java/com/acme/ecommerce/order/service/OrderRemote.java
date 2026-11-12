package com.acme.ecommerce.order.service;

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
import com.acme.ecommerce.user.api.dto.AddressResponse;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import feign.FeignException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls to inventory, payment and promotion. "Strict" methods translate failures into errors the
 * caller must handle; "best-effort" methods only log (used for compensation, where the order state
 * has already been decided).
 */
@Component
public class OrderRemote {

  private static final Logger log = LoggerFactory.getLogger(OrderRemote.class);

  private final InventoryFeignClient inventory;
  private final PaymentFeignClient payments;
  private final PromotionFeignClient promotions;
  private final UserInternalFeignClient usersInternal;
  private final SellerInternalFeignClient sellers;

  public OrderRemote(
      InventoryFeignClient inventory,
      PaymentFeignClient payments,
      PromotionFeignClient promotions,
      UserInternalFeignClient usersInternal,
      SellerInternalFeignClient sellers) {
    this.inventory = inventory;
    this.payments = payments;
    this.promotions = promotions;
    this.usersInternal = usersInternal;
    this.sellers = sellers;
  }

  // ---- sellers -----------------------------------------------------------------------------

  /** The APPROVED seller account of a user, or null (not a seller / suspended). 503 if unreachable. */
  public java.util.UUID approvedSellerId(UUID userId) {
    try {
      SellerInternalFeignClient.SellerRef ref = sellers.byUser(userId);
      return ref != null && "APPROVED".equalsIgnoreCase(ref.status()) ? ref.sellerId() : null;
    } catch (FeignException.NotFound e) {
      return null;
    } catch (FeignException e) {
      throw RemoteCalls.unavailable("seller-service", e);
    }
  }

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

  public void restoreStockBestEffort(UUID orderId) {
    try {
      inventory.restore(orderId);
    } catch (RuntimeException e) {
      log.error("Could not restore stock for order {}: {}", orderId, e.toString());
    }
    try {
      promotions.releaseDeals(orderId);
    } catch (RuntimeException e) {
      log.error("Could not release deal claims for order {}: {}", orderId, e.toString());
    }
  }

  public void releaseStock(UUID orderId, UUID returnId, List<ReturnLine> lines) {
    try {
      inventory.release(
          orderId,
          new InventoryFeignClient.ReleaseRequest(
              returnId, lines.stream().map(l -> new Item(l.productId(), l.variantId(), l.quantity(), l.offerId())).toList()));
    } catch (RuntimeException e) {
      log.error("Could not restock return {} of order {}: {}", returnId, orderId, e.toString());
    }
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

  public void releaseCouponBestEffort(UUID orderId) {
    try {
      promotions.release(orderId);
    } catch (RuntimeException e) {
      log.error("Could not release coupon for order {}: {}", orderId, e.toString());
    }
  }

  /** Active deal prices for the lines; empty on any failure (deals are best-effort at pricing). */
  public List<PromotionFeignClient.DealPrice> dealPrices(
      UUID userId,
      List<PromotionFeignClient.DealKey> keys) {
    try {
      PromotionFeignClient.DealPricesResponse r =
          promotions.dealPrices(new PromotionFeignClient.DealPricesRequest(keys, userId));
      return r == null || r.prices() == null ? List.of() : r.prices();
    } catch (RuntimeException e) {
      log.warn("Deal prices unavailable, using list prices: {}", e.toString());
      return List.of();
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

  /** Strict refund; destination SOURCE (default) or WALLET. */
  public void refund(
      UUID orderId, BigDecimal amount, String reason, String idempotencyKey, String destination) {
    try {
      payments.refund(orderId, new RefundRequest(amount, reason, idempotencyKey, destination));
    } catch (FeignException e) {
      throw RemoteCalls.translate("payment-service", e);
    }
  }

  public void codCollectedBestEffort(UUID orderId) {
    try {
      payments.codCollected(orderId);
    } catch (RuntimeException e) {
      log.warn("Could not mark COD collected for order {}: {}", orderId, e.toString());
    }
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
          new PaymentFeignClient.WalletDebit(userId, amount, currency, orderId, "order-debit-" + orderId));
    } catch (FeignException e) {
      throw RemoteCalls.translate("payment-service", e);
    }
  }

  /** Gives the wallet part of a cancelled order back. Idempotent, best effort (logged). */
  public void creditWalletBackBestEffort(
      UUID userId, BigDecimal amount, String currency, UUID orderId) {
    try {
      payments.walletCredit(
          new PaymentFeignClient.WalletCredit(
              userId, amount, currency, "REFUND", orderId.toString(), "order-wallet-back-" + orderId));
    } catch (RuntimeException e) {
      log.error("Could not return wallet amount {} for order {}: {}", amount, orderId, e.toString());
    }
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

  public void releaseCoinsBestEffort(UUID orderId) {
    try {
      promotions.releaseCoins(orderId);
    } catch (RuntimeException e) {
      log.error("Could not release coins for order {}: {}", orderId, e.toString());
    }
  }
}
