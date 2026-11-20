package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.RefundResult;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** O-05a T4: the refund call returns the refund-row status; errors still translate. */
class OrderRemoteRefundTest {

  private final PaymentFeignClient payments = mock(PaymentFeignClient.class);
  private final OrderRemote remote =
      new OrderRemote(null, payments, null, null, null, mock(WalletCreditBacks.class), null);
  private final UUID order = UUID.randomUUID();

  private String refund() {
    return remote.refund(order, new BigDecimal("10.00"), "r", "k", null);
  }

  @ParameterizedTest
  @CsvSource({"PENDING,PENDING", "SUCCEEDED,SUCCEEDED", "FAILED,FAILED"})
  @DisplayName("the refund-row status is passed through")
  void passesThrough(String sent, String expected) {
    when(payments.refund(any(), any()))
        .thenReturn(new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, sent));

    assertThat(refund()).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "a null refundStatus for a card refund is PENDING, for a wallet destination SUCCEEDED")
  void nullStatus() {
    when(payments.refund(any(), any()))
        .thenReturn(new RefundResult("REFUNDED", BigDecimal.TEN, null));

    assertThat(refund()).isEqualTo("PENDING");
    assertThat(remote.refund(order, BigDecimal.TEN, "r", "k", "WALLET")).isEqualTo("SUCCEEDED");
  }

  private static FeignException failure(int status, String body) {
    Request req =
        Request.create(
            Request.HttpMethod.POST, "http://payment-service/x", Map.of(), null, null, null);
    return FeignException.errorStatus(
        "refund",
        Response.builder()
            .status(status)
            .reason("r")
            .request(req)
            .headers(Map.of())
            .body(body, StandardCharsets.UTF_8)
            .build());
  }

  @Test
  @DisplayName("a 503 stays a 503")
  void unavailable() {
    when(payments.refund(any(), any())).thenThrow(failure(503, ""));

    assertThatThrownBy(this::refund)
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
  }

  @Test
  @DisplayName("a 404 payment is translated to PAYMENT_NOT_FOUND")
  void notFound() {
    when(payments.refund(any(), any()))
        .thenThrow(
            failure(404, "{\"code\":\"PAYMENT_NOT_FOUND\",\"detail\":\"Payment not found\"}"));

    assertThatThrownBy(this::refund)
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));
  }
}
