package com.acme.ecommerce.seller.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.acme.ecommerce.seller.ledger.LedgerService.Part;
import com.acme.ecommerce.seller.ledger.OrderEventConsumer.OrderEvt;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** order.events consumer: strict RETURN_REFUNDED, no refundAmount fallback, shipments tracked. */
@ExtendWith(OutputCaptureExtension.class)
class OrderEventConsumerTest {

  private static final String PLATFORM = "00000000-0000-0000-0000-00000000a11e";

  private final LedgerService ledger = mock(LedgerService.class);
  private final OrderEventConsumer.Listener listener =
      new OrderEventConsumer.Listener(ledger, PLATFORM);

  private final String order = UUID.randomUUID().toString();
  private final String shipment = UUID.randomUUID().toString();
  private final String returnId = UUID.randomUUID().toString();

  private OrderEvt returnRefunded(String rid, String rship, BigDecimal items) {
    return new OrderEvt(
        "RETURN_REFUNDED",
        order,
        "DELIVERED",
        "REFUNDED",
        new BigDecimal("100.00"),
        "INR",
        List.of(),
        null,
        rid,
        rship,
        new BigDecimal("95.00"),
        items);
  }

  @Test
  @DisplayName(
      "a RETURN_REFUNDED missing returnId, returnShipmentId or returnItemsValue is alerted, rejected and posts nothing")
  void malformedReturnRefundedThrowsNonRetryableAndPostsNothing(CapturedOutput output) {
    List<OrderEvt> bad =
        List.of(
            returnRefunded(null, shipment, new BigDecimal("100.00")),
            returnRefunded(returnId, null, new BigDecimal("100.00")),
            returnRefunded(returnId, shipment, null));

    for (OrderEvt e : bad) {
      for (int replay = 0; replay < 2; replay++) {
        assertThatThrownBy(() -> listener.onEvent(e)).isInstanceOf(IllegalArgumentException.class);
      }
    }

    assertThat(output.getAll()).contains("LEDGER ALERT malformed RETURN_REFUNDED order=" + order);
    verify(ledger, org.mockito.Mockito.never())
        .reverseReturn(any(), any(), any(), any(), anyString());
    verify(ledger, org.mockito.Mockito.never()).reverseRefund(any(), any());
  }

  @Test
  @DisplayName("each malformed event is alerted on its own, with present/missing/invalid per id")
  void eachMalformedReturnIsAlertedWithFieldStates(CapturedOutput output) {
    record Case(OrderEvt event, String ids) {}
    List<Case> cases =
        List.of(
            new Case(
                returnRefunded(returnId, shipment, BigDecimal.ZERO),
                "returnId present returnShipmentId present"),
            new Case(
                returnRefunded(returnId, shipment, new BigDecimal("-5.00")),
                "returnId present returnShipmentId present"),
            new Case(
                returnRefunded("not-a-uuid\nFORGED", shipment, new BigDecimal("1.00")),
                "returnId invalid returnShipmentId present"),
            new Case(
                returnRefunded(returnId, "nope", new BigDecimal("1.00")),
                "returnId present returnShipmentId invalid"),
            new Case(
                returnRefunded(null, shipment, new BigDecimal("1.00")),
                "returnId missing returnShipmentId present"));

    for (Case c : cases) {
      String seen = output.getAll();
      int before = seen.split("LEDGER ALERT", -1).length - 1;
      assertThatThrownBy(() -> listener.onEvent(c.event()))
          .isInstanceOf(IllegalArgumentException.class);
      String now = output.getAll();
      assertThat(now.split("LEDGER ALERT", -1).length - 1).isEqualTo(before + 1);
      assertThat(now.substring(seen.length())).contains(c.ids());
    }

    assertThat(output.getAll()).doesNotContain("FORGED");
    verify(ledger, org.mockito.Mockito.never())
        .reverseReturn(any(), any(), any(), any(), anyString());
  }

  @Test
  @DisplayName("a well-formed RETURN_REFUNDED reverses the items value, not the refund amount")
  void wellFormedReturnRefundedCallsReverseReturnWithItemsValue() {
    listener.onEvent(returnRefunded(returnId, shipment, new BigDecimal("100.00")));

    verify(ledger)
        .reverseReturn(
            UUID.fromString(order),
            UUID.fromString(shipment),
            UUID.fromString(returnId),
            new BigDecimal("100.00"),
            "INR");
    verify(ledger, org.mockito.Mockito.never()).reverseRefund(any(), any());
  }

  @Test
  @DisplayName("an event with shipments is tracked before its postings")
  void eventsWithShipmentsAreTracked() {
    List<Part> parts =
        List.of(new Part(shipment, PLATFORM, "DELIVERED", new BigDecimal("100.00"), List.of()));
    listener.onEvent(
        new OrderEvt(
            "SHIPMENT_STATUS_CHANGED",
            order,
            "DELIVERED",
            "PAID",
            new BigDecimal("100.00"),
            "INR",
            parts,
            shipment,
            null,
            null,
            null,
            null));

    InOrder o = inOrder(ledger);
    o.verify(ledger).track(UUID.fromString(order), parts, "INR");
    o.verify(ledger).recordDelivered(UUID.fromString(order), parts.get(0), "INR");
  }

  @Test
  @DisplayName("an event without shipments is not tracked")
  void eventsWithoutShipmentsAreNotTracked() {
    listener.onEvent(
        new OrderEvt(
            "ORDER_STATUS_CHANGED",
            order,
            "PLACED",
            null,
            null,
            "INR",
            null,
            null,
            null,
            null,
            null,
            null));

    verifyNoInteractions(ledger);
  }
}
