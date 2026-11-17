package com.acme.ecommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import com.acme.ecommerce.inventory.config.InventoryProperties;
import com.acme.ecommerce.inventory.event.InventoryEventPublisher;
import com.acme.ecommerce.inventory.repository.InventoryRepository;
import com.acme.ecommerce.inventory.repository.ReservationReleaseRepository;
import com.acme.ecommerce.inventory.repository.ReservationRepository;
import com.acme.ecommerce.inventory.repository.VariantStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseRepository;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import com.acme.ecommerce.inventory.service.ReservationService.Line;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TD-P6-04: missing stock rows fail closed; a release cannot create stock. */
class ReservationServiceStockRulesTest {

  private final UUID order = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();

  private InventoryRepository legacy;
  private VariantStockRepository variants;
  private WarehouseStockRepository stock;
  private ReservationRepository reservations;
  private ReservationReleaseRepository releases;
  private InventoryProperties properties;
  private ReservationService service;

  @BeforeEach
  void setUp() {
    legacy = mock(InventoryRepository.class);
    variants = mock(VariantStockRepository.class);
    stock = mock(WarehouseStockRepository.class);
    reservations = mock(ReservationRepository.class);
    releases = mock(ReservationReleaseRepository.class);
    properties = new InventoryProperties();
    service =
        new ReservationService(
            legacy,
            variants,
            stock,
            mock(WarehouseRepository.class),
            reservations,
            releases,
            new FixedTimeProvider(Instant.parse("2026-11-17T00:00:00Z")),
            mock(InventoryEventPublisher.class),
            properties);
    when(reservations.findByOrderId(order)).thenReturn(List.of());
    when(stock.lockForLine(any(), any(), any())).thenReturn(List.of());
  }

  @Test
  @DisplayName("a line with no stock rows is out of stock by default")
  void missingRowsAreRejected() {
    assertThatThrownBy(
            () -> service.reserve(order, "560001", List.of(new Line(product, null, null, 2))))
        .isInstanceOf(OutOfStockException.class);
    verify(reservations, never()).save(any());
  }

  @Test
  @DisplayName("UNLIMITED keeps the old behaviour as an escape hatch")
  void unlimitedEscapeHatch() {
    properties.getStock().setMissingRows(InventoryProperties.MissingRows.UNLIMITED);

    assertThat(service.reserve(order, "560001", List.of(new Line(product, null, null, 2))))
        .isEmpty();
  }

  @Test
  @DisplayName("releasing a line that was never reserved adds no stock")
  void unmatchedReleaseAddsNothing() {
    service.release(order, "return-1", List.of(new Line(product, null, null, 5)));

    verifyNoInteractions(legacy, variants);
    verify(stock, never()).add(any(), anyInt());
    verify(stock, never()).upsert(any(), any(), any(), any(), any(), anyInt());
    verify(releases).save(any());
  }

  @Test
  @DisplayName("a release is recorded once per key (idempotent)")
  void releaseIsIdempotent() {
    when(releases.existsByOrderIdAndReleaseKey(eq(order), eq("return-1"))).thenReturn(true);

    service.release(order, "return-1", List.of(new Line(product, null, null, 5)));

    verify(releases, never()).save(any());
  }
}
