package com.acme.ecommerce.order.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.ShipmentResponse;
import com.acme.ecommerce.order.outbox.Outbox;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * F-14 B2 (AC-8): cancel events carry no return id and no refund amount, so seller-service's
 * OrderEventConsumer (which reverses only on RETURN_REFUNDED with both set) reverses nothing.
 */
class CancelEventShapeTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, OrderEvent> kafka = mock(KafkaTemplate.class);

  private final Outbox outbox = mock(Outbox.class);
  private final OrderEventPublisher publisher = new OrderEventPublisher(kafka, outbox);

  private OrderResponse order() {
    OrderResponse o = mock(OrderResponse.class);
    when(o.id()).thenReturn(UUID.randomUUID());
    when(o.items()).thenReturn(List.of());
    when(o.subtotalAmount()).thenReturn(new BigDecimal("300.00"));
    when(o.paymentStatus()).thenReturn("PARTIALLY_REFUNDED");
    return o;
  }

  private OrderEvent captured() {
    ArgumentCaptor<OrderEvent> c = ArgumentCaptor.forClass(OrderEvent.class);
    verify(outbox).enqueue(eq(Kind.ORDER_EVENT), any(), c.capture(), any());
    return c.getValue();
  }

  @Test
  @DisplayName("SHIPMENT_STATUS_CHANGED CANCELLED has no returnId, refundAmount or return values")
  void shipmentCancelled_noReturnFields() {
    ShipmentResponse s = mock(ShipmentResponse.class);
    when(s.id()).thenReturn(UUID.randomUUID());
    when(s.status()).thenReturn("CANCELLED");

    publisher.publishShipment(OrderEventType.SHIPMENT_STATUS_CHANGED, order(), s);

    OrderEvent e = captured();
    assertThat(e.status()).isEqualTo("CANCELLED");
    assertThat(e.returnId()).isNull();
    assertThat(e.returnShipmentId()).isNull();
    assertThat(e.refundAmount()).isNull();
    assertThat(e.returnItemsValue()).isNull();
  }

  @Test
  @DisplayName("ORDER_CANCELLED has no returnId, refundAmount or return values")
  void orderCancelled_noReturnFields() {
    publisher.publish(OrderEventType.ORDER_CANCELLED, order());

    OrderEvent e = captured();
    assertThat(e.returnId()).isNull();
    assertThat(e.refundAmount()).isNull();
    assertThat(e.returnItemsValue()).isNull();
  }
}
