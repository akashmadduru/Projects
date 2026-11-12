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

  public OrderRemote(
      InventoryFeignClient inventory, PaymentFeignClient payments, PromotionFeignClient promotions) {
    this.inventory = inventory;
    this.payments = payments;
    this.promotions = promotions;
  }

  // ---- inventory ---------------------------------------------------------------------------

  public void reserveStock(UUID orderId, List<ReturnLine> lines) {
    try {
      inventory.reserve(
          new InventoryFeignClient.ReserveRequest(
              orderId, lines.stream().map(l -> new Item(l.productId(), l.quantity())).toList()));
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
  }

  public void releaseStock(UUID orderId, UUID returnId, List<ReturnLine> lines) {
    try {
      inventory.release(
          orderId,
          new InventoryFeignClient.ReleaseRequest(
              returnId, lines.stream().map(l -> new Item(l.productId(), l.quantity())).toList()));
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
    try {
      payments.refund(orderId, new RefundRequest(amount, reason, idempotencyKey));
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
}
