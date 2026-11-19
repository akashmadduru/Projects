package com.acme.ecommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.repository.WarehouseRepository;
import com.acme.ecommerce.inventory.repository.WarehouseRepository.Warehouse;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import com.acme.ecommerce.inventory.service.ReservationService.Line;
import com.acme.ecommerce.inventory.support.AbstractInventoryIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** offerTotal, unreachable orphan rows, and write-vs-reserve concurrency on a real database. */
class SellerStockIntegrationTest extends AbstractInventoryIntegrationTest {

  @Autowired private WarehouseRepository warehouses;
  @Autowired private WarehouseStockRepository stock;
  @Autowired private WarehouseService warehouseService;
  @Autowired private ReservationService reservations;

  private Warehouse warehouse(UUID seller) {
    String code = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    return warehouses.insert(code, "test", seller, "560001", "KA");
  }

  @Test
  @DisplayName("offerTotal sums one seller's offer rows for the variant, excluding platform/others")
  void offerTotalFilters() {
    UUID p = UUID.randomUUID();
    UUID v = UUID.randomUUID();
    UUID seller = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID offer = UUID.randomUUID();
    Warehouse w1 = warehouse(seller);
    Warehouse w2 = warehouse(seller);
    Warehouse wp = warehouse(Platform.SELLER_ID);
    Warehouse wo = warehouse(other);
    stock.upsert(p, null, seller, offer, w1.id(), 3);
    stock.upsert(p, null, seller, offer, w2.id(), 4);
    stock.upsert(p, v, seller, offer, w1.id(), 50); // a different variant
    stock.upsert(p, null, Platform.SELLER_ID, null, wp.id(), 100); // platform: no offer id
    stock.upsert(p, null, other, UUID.randomUUID(), wo.id(), 1000); // another seller

    assertThat(stock.offerTotal(p, null, seller)).isEqualTo(7);
    assertThat(stock.offerTotal(p, v, seller)).isEqualTo(50);
    assertThat(stock.offerTotal(p, null, Platform.SELLER_ID)).isZero();
    assertThat(stock.offerTotal(p, null, UUID.randomUUID())).isZero();
  }

  @Test
  @DisplayName("orphan rows (offer discarded) cannot be reserved by a stale offerId")
  void orphanRowsAreUnreachable() {
    UUID p = UUID.randomUUID();
    UUID seller = UUID.randomUUID();
    UUID deleted = UUID.randomUUID();
    Warehouse w = warehouse(seller);
    stock.upsert(p, null, seller, deleted, w.id(), 0);

    assertThatThrownBy(
            () ->
                reservations.reserve(
                    UUID.randomUUID(),
                    "560001",
                    List.of(new Line(p, null, UUID.randomUUID(), 1, seller))))
        .isInstanceOf(OutOfStockException.class);
    assertThat(stock.offerTotal(p, null, seller)).isZero();
  }

  @Test
  @DisplayName("concurrent reservations of a seller offer never go negative or lose a decrement")
  void concurrentReservationsAreConsistent() throws Exception {
    UUID p = UUID.randomUUID();
    UUID seller = UUID.randomUUID();
    UUID offer = UUID.randomUUID();
    Warehouse w = warehouse(seller);
    warehouseService.setStock(p, null, seller, offer, w.code(), 10);

    ExecutorService pool = Executors.newFixedThreadPool(8);
    AtomicInteger ok = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      futures.add(
          pool.submit(
              () -> {
                try {
                  reservations.reserve(
                      UUID.randomUUID(), "560001", List.of(new Line(p, null, offer, 1, seller)));
                  ok.incrementAndGet();
                } catch (OutOfStockException expected) {
                  // sold out
                }
              }));
    }
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();

    assertThat(ok.get()).isEqualTo(10);
    assertThat(stock.offerTotal(p, null, seller)).isZero();
  }
}
