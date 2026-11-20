package com.acme.ecommerce.promotion.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.acme.ecommerce.common.web.kafka.KafkaJson;
import com.acme.ecommerce.promotion.config.PromotionProperties;
import com.acme.ecommerce.promotion.service.CoinService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Routing and the scoped age-gate exemption for RETURN_REFUNDED (O-05b, C1). */
class OrderEventListenerReturnTest {

  private final CoinService coins = mock(CoinService.class);
  private final OrderEventListener listener =
      new OrderEventListener(coins, new PromotionProperties());
  private final UUID order = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private final UUID ret = UUID.randomUUID();

  private static String ago(long days) {
    return Instant.now().minus(days, ChronoUnit.DAYS).toString();
  }

  private OrderEvent event(String type, String payStatus, String at, String returnId) {
    return new OrderEvent(
        "e1",
        type,
        order.toString(),
        user.toString(),
        null,
        payStatus,
        new BigDecimal("300"),
        "INR",
        at,
        returnId,
        returnId == null ? null : new BigDecimal("100"),
        returnId == null ? null : new BigDecimal("300"));
  }

  @Test
  @DisplayName("C1a: an old RETURN_REFUNDED with returnId is still processed")
  void oldReturnWithIdProcessed() {
    listener.onOrderEvent(event("RETURN_REFUNDED", "REFUNDED", ago(30), ret.toString()));

    verify(coins)
        .onReturnRefunded(
            eq(order),
            eq(user),
            eq(ret),
            eq(new BigDecimal("100")),
            eq(new BigDecimal("300")),
            eq(true));
  }

  @Test
  @DisplayName("C1b: an old legacy RETURN_REFUNDED without returnId is dropped")
  void oldLegacyDropped() {
    listener.onOrderEvent(event("RETURN_REFUNDED", "REFUNDED", ago(30), null));

    verifyNoInteractions(coins);
  }

  @Test
  @DisplayName("C1c: a fresh legacy RETURN_REFUNDED goes to onFullyRefunded")
  void freshLegacyFullRefund() {
    listener.onOrderEvent(event("RETURN_REFUNDED", "REFUNDED", ago(0), null));

    verify(coins).onFullyRefunded(order, user);
    verify(coins, never()).onReturnRefunded(any(), any(), any(), any(), any(), anyBoolean());
  }

  @Test
  @DisplayName("C1d: an old non-return event is still dropped")
  void oldOtherDropped() {
    listener.onOrderEvent(event("ORDER_STATUS_CHANGED", null, ago(30), null));

    verifyNoInteractions(coins);
  }

  @Test
  @DisplayName("a non-completing return routes with completes=false")
  void partialRoutes() {
    listener.onOrderEvent(event("RETURN_REFUNDED", "PARTIALLY_REFUNDED", ago(0), ret.toString()));

    verify(coins)
        .onReturnRefunded(
            eq(order),
            eq(user),
            eq(ret),
            eq(new BigDecimal("100")),
            eq(new BigDecimal("300")),
            eq(false));
  }

  @Test
  @DisplayName("C1e: new fields deserialise, and unknown fields are ignored")
  void deserialises() throws Exception {
    String json =
        "{\"type\":\"RETURN_REFUNDED\",\"orderId\":\"o\",\"returnId\":\"r1\","
            + "\"returnItemsValue\":100.50,\"orderItemsValue\":300,\"somethingNew\":1}";
    OrderEvent e =
        KafkaJson.eventDeserializer(OrderEvent.class, "com.acme.ecommerce.promotion.event")
            .deserialize("order.events", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThat(e.returnId()).isEqualTo("r1");
    assertThat(e.returnItemsValue()).isEqualByComparingTo("100.50");
    assertThat(e.orderItemsValue()).isEqualByComparingTo("300");
  }
}
