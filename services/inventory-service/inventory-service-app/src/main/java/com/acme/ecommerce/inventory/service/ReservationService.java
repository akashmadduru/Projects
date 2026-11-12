package com.acme.ecommerce.inventory.service;

import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.inventory.domain.Inventory;
import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.domain.Reservation;
import com.acme.ecommerce.inventory.domain.ReservationRelease;
import com.acme.ecommerce.inventory.event.InventoryEventPublisher;
import com.acme.ecommerce.inventory.event.InventoryEventType;
import com.acme.ecommerce.inventory.repository.InventoryRepository;
import com.acme.ecommerce.inventory.repository.ReservationReleaseRepository;
import com.acme.ecommerce.inventory.repository.ReservationRepository;
import com.acme.ecommerce.inventory.repository.VariantStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseRepository;
import com.acme.ecommerce.inventory.repository.WarehouseRepository.Warehouse;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository.Row;
import com.acme.ecommerce.inventory.service.OutOfStockException.Shortfall;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order stock reservations. Lines are keyed by (productId, variantId nullable, offerId nullable).
 * Stock lives per warehouse in warehouse_stock; a line is routed to the warehouse nearest to the
 * ship-to pincode that has enough stock, and split across warehouses when none has enough. A line
 * with no stock rows at all is unlimited (as before). Platform stock additionally keeps the legacy
 * stock_levels / variant_stock totals in step (they back events and availableQuantity).
 */
@Service
public class ReservationService {

  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

  public record Line(UUID productId, UUID variantId, UUID offerId, int quantity) {}

  public record Allocation(
      UUID productId,
      UUID variantId,
      UUID offerId,
      UUID sellerId,
      UUID warehouseId,
      String warehouseCode,
      String pincode,
      int quantity) {}

  private record Key(UUID productId, UUID variantId, UUID offerId) {}

  private final InventoryRepository inventory;
  private final VariantStockRepository variants;
  private final WarehouseStockRepository stock;
  private final WarehouseRepository warehouses;
  private final ReservationRepository reservations;
  private final ReservationReleaseRepository releases;
  private final TimeProvider timeProvider;
  private final InventoryEventPublisher events;

  public ReservationService(
      InventoryRepository inventory,
      VariantStockRepository variants,
      WarehouseStockRepository stock,
      WarehouseRepository warehouses,
      ReservationRepository reservations,
      ReservationReleaseRepository releases,
      TimeProvider timeProvider,
      InventoryEventPublisher events) {
    this.inventory = inventory;
    this.variants = variants;
    this.stock = stock;
    this.warehouses = warehouses;
    this.reservations = reservations;
    this.releases = releases;
    this.timeProvider = timeProvider;
    this.events = events;
  }

  @Transactional
  public List<Allocation> reserve(UUID orderId, String pincode, List<Line> lines) {
    if (!reservations.findByOrderId(orderId).isEmpty()) {
      return allocations(orderId);
    }
    Map<Key, Integer> wanted = merge(lines);
    Map<Key, List<Row>> rowsByKey = new LinkedHashMap<>();
    List<Shortfall> shortfalls = new ArrayList<>();
    List<Key> ordered = new ArrayList<>(wanted.keySet());
    ordered.sort(
        Comparator.comparing((Key k) -> k.productId().toString())
            .thenComparing(k -> String.valueOf(k.variantId()))
            .thenComparing(k -> String.valueOf(k.offerId())));
    for (Key k : ordered) {
      List<Row> rows = stock.lockForLine(k.productId(), k.variantId(), k.offerId());
      if (rows.isEmpty() && k.offerId() != null) {
        // Platform offer (its stock rows carry no offer id) or an offer without stock rows.
        rows = stock.lockForLine(k.productId(), k.variantId(), null);
      }
      if (rows.isEmpty()) {
        log.info("No stock rows for {}; treating as unlimited", k);
        continue;
      }
      int total = rows.stream().mapToInt(Row::quantity).sum();
      if (total < wanted.get(k)) {
        shortfalls.add(new Shortfall(k.productId(), wanted.get(k), Math.max(total, 0)));
      }
      rowsByKey.put(k, rows);
    }
    if (!shortfalls.isEmpty()) {
      throw new OutOfStockException(shortfalls);
    }

    Instant now = timeProvider.now();
    List<Allocation> out = new ArrayList<>();
    for (Key k : ordered) {
      List<Row> rows = rowsByKey.get(k);
      if (rows == null) {
        continue;
      }
      int need = wanted.get(k);
      List<Row> sorted = new ArrayList<>(rows);
      sorted.sort(
          Comparator.comparingInt((Row r) -> proximity(pincode, r.pincode()))
              .thenComparing(Comparator.comparingInt(Row::quantity).reversed())
              .thenComparing(Row::warehouseCode));
      List<Row> picks = new ArrayList<>();
      List<Integer> amounts = new ArrayList<>();
      Row single = sorted.stream().filter(r -> r.quantity() >= need).findFirst().orElse(null);
      if (single != null) {
        picks.add(single);
        amounts.add(need);
      } else {
        int remaining = need;
        for (Row r : sorted) {
          if (remaining <= 0) {
            break;
          }
          int take = Math.min(r.quantity(), remaining);
          if (take > 0) {
            picks.add(r);
            amounts.add(take);
            remaining -= take;
          }
        }
      }
      for (int i = 0; i < picks.size(); i++) {
        Row r = picks.get(i);
        int amount = amounts.get(i);
        stock.add(r.id(), -amount);
        reservations.save(
            Reservation.of(
                orderId,
                k.productId(),
                k.variantId(),
                k.offerId(),
                r.sellerId(),
                r.warehouseId(),
                amount,
                now));
        if (Platform.SELLER_ID.equals(r.sellerId())) {
          mirrorLegacy(k.productId(), k.variantId(), -amount, now);
        }
        out.add(
            new Allocation(
                k.productId(),
                k.variantId(),
                k.offerId(),
                r.sellerId(),
                r.warehouseId(),
                r.warehouseCode(),
                r.pincode(),
                amount));
      }
    }
    return out;
  }

  /** The allocations recorded for an order (empty when nothing was stock-tracked). */
  @Transactional(readOnly = true)
  public List<Allocation> allocations(UUID orderId) {
    List<Reservation> rs = reservations.findByOrderId(orderId);
    Map<UUID, Warehouse> byId =
        warehouses
            .byIds(
                rs.stream()
                    .map(Reservation::getWarehouseId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(Warehouse::id, Function.identity()));
    return rs.stream()
        .map(
            r -> {
              Warehouse w = r.getWarehouseId() == null ? null : byId.get(r.getWarehouseId());
              return new Allocation(
                  r.getProductId(),
                  r.getVariantId(),
                  r.getOfferId(),
                  r.getSellerId(),
                  r.getWarehouseId(),
                  w == null ? null : w.code(),
                  w == null ? null : w.pincode(),
                  r.getQuantity());
            })
        .toList();
  }

  @Transactional
  public void restoreAll(UUID orderId) {
    Instant now = timeProvider.now();
    for (Reservation r : reservations.findByOrderId(orderId)) {
      int restored = r.restore(r.outstanding());
      if (restored > 0) {
        addBack(r, restored, now);
      }
    }
  }

  @Transactional
  public void release(UUID orderId, String releaseKey, List<Line> lines) {
    String key = releaseKey == null || releaseKey.isBlank() ? "-" : releaseKey;
    if (releases.existsByOrderIdAndReleaseKey(orderId, key)) {
      return;
    }
    Instant now = timeProvider.now();
    List<Reservation> all = reservations.findByOrderId(orderId);
    for (Map.Entry<Key, Integer> e : merge(lines).entrySet()) {
      Key k = e.getKey();
      int remaining = e.getValue();
      boolean matched = false;
      for (Reservation r : all) {
        if (!r.getProductId().equals(k.productId())
            || !Objects.equals(r.getVariantId(), k.variantId())
            || (k.offerId() != null
                && r.getOfferId() != null
                && !k.offerId().equals(r.getOfferId()))) {
          continue;
        }
        matched = true;
        // Keep restoreAll() from double-restoring what a return already put back.
        int restored = r.restore(remaining);
        if (restored > 0) {
          addBack(r, restored, now);
          remaining -= restored;
        }
        if (remaining <= 0) {
          break;
        }
      }
      if (!matched) {
        // No reservation was recorded (unlimited line): keep the old behaviour of crediting the
        // platform totals so counts do not drift.
        mirrorLegacy(k.productId(), k.variantId(), e.getValue(), now);
      }
      releases.save(
          ReservationRelease.of(orderId, key, k.productId(), k.variantId(), e.getValue(), now));
    }
  }

  private void addBack(Reservation r, int amount, Instant now) {
    if (r.getWarehouseId() != null) {
      UUID seller = r.getSellerId() == null ? Platform.SELLER_ID : r.getSellerId();
      Row row = stock.find(r.getProductId(), r.getVariantId(), seller, r.getWarehouseId()).orElse(null);
      if (row != null) {
        stock.add(row.id(), amount);
      } else {
        stock.upsert(r.getProductId(), r.getVariantId(), seller, r.getOfferId(), r.getWarehouseId(), amount);
      }
      if (!Platform.SELLER_ID.equals(seller)) {
        return;
      }
    }
    mirrorLegacy(r.getProductId(), r.getVariantId(), amount, now);
  }

  /** Applies a delta to the legacy platform totals and publishes the usual events. */
  private void mirrorLegacy(UUID productId, UUID variantId, int delta, Instant now) {
    if (variantId != null) {
      if (variants.quantity(variantId).isPresent()) {
        variants.adjust(variantId, productId, delta);
        publishVariant(productId, variantId);
      }
      return;
    }
    List<Inventory> rows = inventory.findByProductIdInOrderByProductId(List.of(productId));
    if (rows.isEmpty()) {
      return;
    }
    Inventory row = rows.get(0);
    row.adjustQuantity(Math.max(0, row.getQuantity() + delta), now);
    publish(row);
  }

  /** 0 = same first 3 digits, 1 = same first digit, 2 = anywhere. */
  static int proximity(String target, String warehousePincode) {
    if (target == null || warehousePincode == null || target.isBlank()) {
      return 2;
    }
    if (target.length() >= 3
        && warehousePincode.length() >= 3
        && target.regionMatches(0, warehousePincode, 0, 3)) {
      return 0;
    }
    if (target.charAt(0) == warehousePincode.charAt(0)) {
      return 1;
    }
    return 2;
  }

  private void publish(Inventory row) {
    events.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, row);
    if (row.getQuantity() <= row.getLowStockThreshold()) {
      events.publishAfterCommit(InventoryEventType.INVENTORY_LOW_STOCK, row);
    }
  }

  private void publishVariant(UUID productId, UUID variantId) {
    events.publishAfterCommit(
        InventoryEventType.INVENTORY_QUANTITY_CHANGED,
        productId,
        variantId,
        variants.quantity(variantId).orElse(null));
  }

  private static Map<Key, Integer> merge(List<Line> lines) {
    Map<Key, Integer> merged = new LinkedHashMap<>();
    for (Line l : lines) {
      Objects.requireNonNull(l.productId());
      merged.merge(new Key(l.productId(), l.variantId(), l.offerId()), l.quantity(), Integer::sum);
    }
    return merged;
  }
}
