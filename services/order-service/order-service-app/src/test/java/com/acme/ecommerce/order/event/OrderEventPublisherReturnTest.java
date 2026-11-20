package com.acme.ecommerce.order.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse.ReturnItemResponse;
import com.acme.ecommerce.order.outbox.Outbox;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

/** The coin feed on RETURN_* events (O-05b): items values for pro-rata coin reversal. */
class OrderEventPublisherReturnTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, OrderEvent> kafka = mock(KafkaTemplate.class);

  private final Outbox outbox = mock(Outbox.class);
  private final OrderEventPublisher publisher = new OrderEventPublisher(kafka, outbox);

  private OrderResponse order(String paymentStatus) {
    OrderResponse o = mock(OrderResponse.class);
    when(o.id()).thenReturn(UUID.randomUUID());
    when(o.items()).thenReturn(List.of());
    when(o.subtotalAmount()).thenReturn(new BigDecimal("300.00"));
    when(o.paymentStatus()).thenReturn(paymentStatus);
    return o;
  }

  private OrderEvent captured() {
    ArgumentCaptor<OrderEvent> c = ArgumentCaptor.forClass(OrderEvent.class);
    verify(outbox).enqueue(eq(Kind.ORDER_EVENT), any(), c.capture(), any());
    return c.getValue();
  }

  @Test
  @DisplayName("RETURN_REFUNDED carries the return items value and the order subtotal")
  void returnCarriesValues() {
    ReturnResponse ret =
        new ReturnResponse(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "REFUNDED",
            null,
            null,
            List.of(
                new ReturnItemResponse(UUID.randomUUID(), null, "A", 2, new BigDecimal("100.00")),
                new ReturnItemResponse(UUID.randomUUID(), null, "B", 1, new BigDecimal("25.50"))),
            new BigDecimal("225.50"),
            null,
            null,
            null,
            null,
            false,
            null,
            null);

    publisher.publishReturn(OrderEventType.RETURN_REFUNDED, order("REFUNDED"), ret);

    OrderEvent e = captured();
    assertThat(e.returnItemsValue()).isEqualByComparingTo("225.50");
    assertThat(e.orderItemsValue()).isEqualByComparingTo("300.00");
    assertThat(e.returnId()).isEqualTo(ret.id());
    assertThat(e.paymentStatus()).isEqualTo("REFUNDED");
  }

  @Test
  @DisplayName("a non-return event leaves both values null")
  void nonReturnNull() {
    publisher.publish(OrderEventType.ORDER_CANCELLED, order("FAILED"));

    OrderEvent e = captured();
    assertThat(e.returnItemsValue()).isNull();
    assertThat(e.orderItemsValue()).isNull();
  }
}
