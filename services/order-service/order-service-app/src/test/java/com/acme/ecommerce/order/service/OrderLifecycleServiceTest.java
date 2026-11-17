package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.domain.HistoryActor;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.service.OrderStore.CancelPlan;
import com.acme.ecommerce.order.service.OrderStore.Result;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TD-P6-03: cancelling an unpaid order voids its payment; a failed late refund is retried. */
class OrderLifecycleServiceTest {

  private final UUID orderId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private OrderStore store;
  private OrderRemote remote;
  private OrderLifecycleService service;

  @BeforeEach
  void setUp() {
    store = mock(OrderStore.class);
    remote = mock(OrderRemote.class);
    service =
        new OrderLifecycleService(
            store, remote, mock(OrderEventPublisher.class), new OrderProperties());
  }

  private OrderResponse order(String status, String method, String paymentStatus) {
    OrderResponse o = mock(OrderResponse.class);
    when(o.id()).thenReturn(orderId);
    when(o.userId()).thenReturn(userId);
    when(o.status()).thenReturn(status);
    when(o.paymentMethod()).thenReturn(method);
    when(o.paymentStatus()).thenReturn(paymentStatus);
    when(o.currency()).thenReturn("INR");
    when(o.totalAmount()).thenReturn(new BigDecimal("499.00"));
    when(o.walletAmount()).thenReturn(BigDecimal.ZERO);
    return o;
  }

  @Test
  @DisplayName("cancelling an unpaid card order cancels its open payment")
  void cancelUnpaidCardOrderVoidsPayment() {
    when(store.cancelPlan(userId, orderId))
        .thenReturn(new CancelPlan(false, false, BigDecimal.ZERO, "INR"));
    OrderResponse cancelled = order("CANCELLED", "CARD", "FAILED");
    when(store.cancel(eq(userId), eq(orderId), eq(HistoryActor.USER), anyString(), eq(false)))
        .thenReturn(new Result(cancelled, true));

    service.cancelByUser(userId, orderId);

    verify(remote).cancelPaymentBestEffort(orderId);
    verify(remote, never()).refund(any(), any(), any(), any());
  }

  @Test
  @DisplayName("cancelling a cash-on-delivery order does not touch payments")
  void cancelCodOrderLeavesPaymentAlone() {
    when(store.cancelPlan(userId, orderId))
        .thenReturn(new CancelPlan(false, false, BigDecimal.ZERO, "INR"));
    OrderResponse cancelled = order("CANCELLED", "COD", "COD_PENDING");
    when(store.cancel(eq(userId), eq(orderId), eq(HistoryActor.USER), anyString(), eq(false)))
        .thenReturn(new Result(cancelled, true));

    service.cancelByUser(userId, orderId);

    verify(remote, never()).cancelPaymentBestEffort(any());
  }

  @Test
  @DisplayName("a payment arriving after cancellation is refunded")
  void latePaymentIsRefunded() {
    OrderResponse cancelled = order("CANCELLED", "CARD", "FAILED");
    when(store.markPaid(orderId, "pay-1")).thenReturn(new Result(cancelled, false));
    when(store.getAny(orderId)).thenReturn(cancelled);

    service.paymentResult(orderId, "SUCCEEDED", "pay-1");

    verify(remote)
        .refund(
            eq(orderId), eq(new BigDecimal("499.00")), anyString(), eq("late-success-" + orderId));
    verify(store).markLateRefund(orderId);
  }

  @Test
  @DisplayName("a failed late refund is reported as 503 so payment-service re-sends the outcome")
  void failedLateRefundIsRetried() {
    OrderResponse cancelled = order("CANCELLED", "CARD", "FAILED");
    when(store.markPaid(orderId, "pay-1")).thenReturn(new Result(cancelled, false));
    doThrow(new IllegalStateException("payment-service down"))
        .when(remote)
        .refund(any(), any(), any(), any());

    assertThatThrownBy(() -> service.paymentResult(orderId, "SUCCEEDED", "pay-1"))
        .isInstanceOf(CodedException.class)
        .satisfies(
            e ->
                assertThat(((CodedException) e).errorCode())
                    .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    verify(store, never()).markLateRefund(any());
  }
}
