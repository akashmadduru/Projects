package com.acme.ecommerce.inventory.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.UpstreamUnavailableException;
import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.service.ReservationService;
import com.acme.ecommerce.inventory.service.ReservationService.Line;
import com.acme.ecommerce.inventory.service.SellerDirectory;
import com.acme.ecommerce.inventory.web.internal.InternalReservationController.ItemDto;
import com.acme.ecommerce.inventory.web.internal.InternalReservationController.ReserveRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Owner resolution before the reservation transaction (rolling-deploy fallback, CTO 4). */
class InternalReservationControllerTest {

  private final UUID order = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private final UUID offer = UUID.randomUUID();
  private final UUID seller = UUID.randomUUID();

  private ReservationService reservations;
  private SellerDirectory sellers;
  private InternalReservationController controller;

  @BeforeEach
  void setUp() {
    reservations = mock(ReservationService.class);
    sellers = mock(SellerDirectory.class);
    controller = new InternalReservationController(reservations, sellers);
  }

  @SuppressWarnings("unchecked")
  private List<Line> reservedLines() {
    ArgumentCaptor<List<Line>> captor = ArgumentCaptor.forClass(List.class);
    verify(reservations).reserve(eq(order), any(), captor.capture());
    return captor.getValue();
  }

  private ReserveRequest request(UUID sellerId, UUID offerId) {
    return new ReserveRequest(
        order, "560001", List.of(new ItemDto(product, null, offerId, 1, sellerId)));
  }

  @Test
  @DisplayName("a null sellerId on an offer line is resolved through the seller directory")
  void nullSellerResolvedViaDirectory() {
    when(sellers.offer(offer))
        .thenReturn(new SellerDirectory.OfferRef(offer, product, null, seller));

    controller.reserve(request(null, offer));

    assertThat(reservedLines())
        .singleElement()
        .satisfies(l -> assertThat(l.sellerId()).isEqualTo(seller));
  }

  @Test
  @DisplayName("an unknown offer (404) leaves the owner null, which is never platform")
  void unknownOfferLeavesOwnerNull() {
    when(sellers.offer(offer)).thenReturn(null);

    controller.reserve(request(null, offer));

    assertThat(reservedLines()).singleElement().satisfies(l -> assertThat(l.sellerId()).isNull());
  }

  @Test
  @DisplayName("a directory outage is a 503 raised before any reservation work")
  void directoryOutageFailsBeforeReserving() {
    when(sellers.offer(offer)).thenThrow(new UpstreamUnavailableException("product-service", null));

    assertThatThrownBy(() -> controller.reserve(request(null, offer)))
        .isInstanceOf(UpstreamUnavailableException.class);

    verifyNoInteractions(reservations);
  }

  @Test
  @DisplayName("a supplied sellerId (or a line with no offer) needs no directory call")
  void suppliedSellerSkipsDirectory() {
    controller.reserve(request(Platform.SELLER_ID, offer));

    verifyNoInteractions(sellers);
    assertThat(reservedLines())
        .singleElement()
        .satisfies(l -> assertThat(l.sellerId()).isEqualTo(Platform.SELLER_ID));
  }
}
