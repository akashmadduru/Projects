package com.acme.ecommerce.inventory.service;

import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.inventory.domain.Inventory;
import com.acme.ecommerce.inventory.domain.Reservation;
import com.acme.ecommerce.inventory.domain.ReservationRelease;
import com.acme.ecommerce.inventory.event.InventoryEventPublisher;
import com.acme.ecommerce.inventory.event.InventoryEventType;
import com.acme.ecommerce.inventory.repository.InventoryRepository;
import com.acme.ecommerce.inventory.repository.ReservationReleaseRepository;
import com.acme.ecommerce.inventory.repository.ReservationRepository;
import com.acme.ecommerce.inventory.service.OutOfStockException.Shortfall;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order-driven stock movements. Decrements the real {@code quantity} (so the existing
 * INVENTORY_QUANTITY_CHANGED event keeps product-service's lastKnownQuantity right). Products with
 * no inventory row are treated as unlimited so the legacy catalog keeps working.
 */
@Service
public class ReservationService {

  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

  public record Line(UUID productId, int quantity) {}

  private final InventoryRepository inventory;
  private final ReservationRepository reservations;
  private final ReservationReleaseRepository releases;
  private final TimeProvider timeProvider;
  private final InventoryEventPublisher events;

  public ReservationService(
      InventoryRepository inventory,
      ReservationRepository reservations,
      ReservationReleaseRepository releases,
      TimeProvider timeProvider,
      InventoryEventPublisher events) {
    this.inventory = inventory;
    this.reservations = reservations;
    this.releases = releases;
    this.timeProvider = timeProvider;
    this.events = events;
  }

  /** All-or-nothing decrement; idempotent by orderId. */
  @Transactional
  public void reserve(UUID orderId, List<Line> lines) {
    if (!reservations.findByOrderId(orderId).isEmpty()) {
      return;
    }
    Map<UUID, Integer> wanted = merge(lines);
    Map<UUID, Inventory> rows =
        inventory.findByProductIdInOrderByProductId(wanted.keySet()).stream()
            .collect(Collectors.toMap(Inventory::getProductId, Function.identity()));

    List<Shortfall> shortfalls = new ArrayList<>();
    for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
      Inventory row = rows.get(e.getKey());
      if (row == null) {
        log.info("No inventory row for product {}; treating as unlimited", e.getKey());
        continue;
      }
      int available = row.getQuantity() - row.getReservedQuantity();
      if (available < e.getValue()) {
        shortfalls.add(new Shortfall(e.getKey(), e.getValue(), Math.max(available, 0)));
      }
    }
    if (!shortfalls.isEmpty()) {
      throw new OutOfStockException(shortfalls);
    }

    Instant now = timeProvider.now();
    for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
      Inventory row = rows.get(e.getKey());
      if (row == null) {
        continue;
      }
      row.adjustQuantity(row.getQuantity() - e.getValue(), now);
      reservations.save(Reservation.of(orderId, e.getKey(), e.getValue(), now));
      publish(row);
    }
  }

  /** Restores whatever of the order's reservation has not been restored yet; idempotent. */
  @Transactional
  public void restoreAll(UUID orderId) {
    Instant now = timeProvider.now();
    for (Reservation r : reservations.findByOrderId(orderId)) {
      int restored = r.restore(r.outstanding());
      if (restored > 0) {
        addBack(r.getProductId(), restored, now);
      }
    }
  }

  /** Partial restock (a return); idempotent by (orderId, releaseKey). */
  @Transactional
  public void release(UUID orderId, String releaseKey, List<Line> lines) {
    String key = releaseKey == null || releaseKey.isBlank() ? "-" : releaseKey;
    if (releases.existsByOrderIdAndReleaseKey(orderId, key)) {
      return;
    }
    Instant now = timeProvider.now();
    Map<UUID, Reservation> byProduct =
        reservations.findByOrderId(orderId).stream()
            .collect(Collectors.toMap(Reservation::getProductId, Function.identity()));
    for (Map.Entry<UUID, Integer> e : merge(lines).entrySet()) {
      Reservation r = byProduct.get(e.getKey());
      if (r != null) {
        // Keep restoreAll() from double-restoring what a return already put back.
        r.restore(e.getValue());
      }
      addBack(e.getKey(), e.getValue(), now);
      releases.save(ReservationRelease.of(orderId, key, e.getKey(), e.getValue(), now));
    }
  }

  private void addBack(UUID productId, int amount, Instant now) {
    List<Inventory> rows = inventory.findByProductIdInOrderByProductId(List.of(productId));
    if (rows.isEmpty()) {
      return;
    }
    Inventory row = rows.get(0);
    row.adjustQuantity(row.getQuantity() + amount, now);
    publish(row);
  }

  private void publish(Inventory row) {
    events.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, row);
    if (row.getQuantity() <= row.getLowStockThreshold()) {
      events.publishAfterCommit(InventoryEventType.INVENTORY_LOW_STOCK, row);
    }
  }

  private static Map<UUID, Integer> merge(List<Line> lines) {
    Map<UUID, Integer> merged = new LinkedHashMap<>();
    for (Line l : lines) {
      merged.merge(l.productId(), l.quantity(), Integer::sum);
    }
    return merged;
  }
}
