package com.acme.ecommerce.order.support;

import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.Order.Allocation;
import com.acme.ecommerce.order.domain.Order.ShipTo;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.Pricing;
import com.acme.ecommerce.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds a saved order with one single-line shipment per amount (F-14 tests). */
public final class CancelFixture {

  /** Shipments are listed in the order the store freezes them (created_at, then id). */
  public record Built(
      UUID orderId, UUID userId, List<UUID> shipmentIds, List<UUID> sellerIds, BigDecimal total) {}

  private CancelFixture() {}

  /**
   * @param method CARD, COD or WALLET (WALLET is placed as a paid wallet order)
   * @param amounts the line amount of each shipment (quantity 1)
   */
  public static Built build(
      OrderRepository orders,
      JdbcTemplate jdbc,
      PaymentMethod method,
      String discount,
      String coins,
      String shipping,
      String wallet,
      String... amounts) {
    Instant now = Instant.now();
    Order o =
        Order.draft(
            UUID.randomUUID(),
            "a@b.c",
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            new ShipTo("N", "1", "l1", null, "c", "s", "560001", "IN"),
            "INR",
            method == PaymentMethod.WALLET ? PaymentMethod.CARD : method,
            now);
    BigDecimal sub = BigDecimal.ZERO;
    List<Allocation> allocations = new ArrayList<>();
    List<UUID> sellers = new ArrayList<>();
    for (int i = 0; i < amounts.length; i++) {
      UUID product = UUID.randomUUID();
      UUID seller = UUID.randomUUID();
      sellers.add(seller);
      BigDecimal amount = new BigDecimal(amounts[i]);
      o.addItem(product, null, null, "sku" + i, "P" + i, amount, 1, null, null, seller);
      allocations.add(
          new Allocation(product, null, null, UUID.randomUUID(), "WH" + i, "560001", 1));
      sub = sub.add(amount);
    }
    BigDecimal disc = new BigDecimal(discount);
    BigDecimal coinVal = new BigDecimal(coins);
    BigDecimal ship = new BigDecimal(shipping);
    o.applyMoney(coinVal.signum() > 0 ? 10 : 0, coinVal, new BigDecimal(wallet), false, null);
    o.begin(
        new Pricing(
            sub,
            disc,
            ship,
            sub.subtract(disc).subtract(coinVal).add(ship),
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            coinVal,
            false),
        null,
        null,
        now);
    o.assignShipments(allocations, Map.of(), pin -> null, now);
    if (method != PaymentMethod.COD) {
      if (method == PaymentMethod.WALLET) {
        o.switchPaymentMethod(PaymentMethod.WALLET);
      }
      o.markPaid(method == PaymentMethod.WALLET ? null : "pay-1", now);
    }
    Order saved = orders.saveAndFlush(o);
    // Distinct, increasing creation times so the freeze order is the listed order.
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < amounts.length; i++) {
      UUID sid =
          jdbc.queryForObject(
              "SELECT s.id FROM orders.order_shipments s JOIN orders.order_items i"
                  + " ON i.shipment_id = s.id WHERE s.order_id = ? AND i.sku = ?",
              UUID.class,
              saved.getId(),
              "sku" + i);
      jdbc.update(
          "UPDATE orders.order_shipments SET created_at = ? WHERE id = ?",
          java.sql.Timestamp.from(now.plusSeconds(i)),
          sid);
      ids.add(sid);
    }
    return new Built(saved.getId(), saved.getUserId(), ids, sellers, saved.getTotalAmount());
  }
}
