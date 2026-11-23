package com.acme.ecommerce.order.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentRowDecodeTest {

  @Test
  @DisplayName("a PaymentResponse-shaped body with extra fields decodes into PaymentRow")
  void decodesWithUnknownFields() throws Exception {
    String json =
        "{\"paymentId\":\"p-1\",\"orderId\":\"o-1\",\"status\":\"COD_PENDING\",\"method\":\"COD\","
            + "\"amount\":380.00,\"currency\":\"INR\",\"mode\":\"RAZORPAY\",\"clientSecret\":null,"
            + "\"somethingNew\":{\"a\":1}}";

    PaymentRow row = new ObjectMapper().readValue(json, PaymentRow.class);

    assertThat(row.paymentId()).isEqualTo("p-1");
    assertThat(row.method()).isEqualTo("COD");
    assertThat(row.status()).isEqualTo("COD_PENDING");
    assertThat(row.amount()).isEqualByComparingTo("380.00");
  }
}
