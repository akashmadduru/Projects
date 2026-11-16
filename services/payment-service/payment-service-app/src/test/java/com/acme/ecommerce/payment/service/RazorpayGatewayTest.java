package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.payment.config.PaymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RazorpayGatewayTest {

  private PaymentProperties props;
  private MockRestServiceServer server;
  private RazorpayGateway gateway;

  @BeforeEach
  void setUp() {
    props = new PaymentProperties();
    props.getRazorpay().setKeyId("rzp_test_id");
    props.getRazorpay().setKeySecret("secret");
    props.getRazorpay().setWebhookSecret("whsec");
    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.razorpay.com");
    server = MockRestServiceServer.bindTo(builder).build();
    gateway = new RazorpayGateway(props, builder.build());
  }

  @Test
  @DisplayName("HMAC-SHA256 hex matches the published test vector")
  void hmacVector() {
    assertThat(RazorpayGateway.hmacHex("key", "The quick brown fox jumps over the lazy dog"))
        .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
  }

  @Test
  @DisplayName("checkout signature: accepts the right one, rejects tampering and nulls")
  void checkoutSignature() {
    String good = RazorpayGateway.hmacHex("secret", "order_1|pay_1");

    assertThat(gateway.verifyCheckoutSignature("order_1", "pay_1", good)).isTrue();
    assertThat(gateway.verifyCheckoutSignature("order_1", "pay_1", good.toUpperCase())).isTrue();
    assertThat(gateway.verifyCheckoutSignature("order_1", "pay_2", good)).isFalse();
    assertThat(gateway.verifyCheckoutSignature("order_2", "pay_1", good)).isFalse();
    assertThat(gateway.verifyCheckoutSignature("order_1", "pay_1", "deadbeef")).isFalse();
    assertThat(gateway.verifyCheckoutSignature("order_1", "pay_1", null)).isFalse();
  }

  @Test
  @DisplayName("webhook signature: needs a secret and a matching body")
  void webhookSignature() {
    String body = "{\"event\":\"payment.captured\"}";
    String good = RazorpayGateway.hmacHex("whsec", body);

    assertThat(gateway.verifyWebhookSignature(body, good)).isTrue();
    assertThat(gateway.verifyWebhookSignature(body + " ", good)).isFalse();
    assertThat(gateway.verifyWebhookSignature(body, null)).isFalse();

    props.getRazorpay().setWebhookSecret("");
    assertThat(gateway.verifyWebhookSignature(body, good)).isFalse();
  }

  @Test
  @DisplayName("createOrder posts paise, currency and receipt")
  void createOrder() {
    server
        .expect(requestTo("https://api.razorpay.com/v1/orders"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.amount").value(49900))
        .andExpect(jsonPath("$.currency").value("INR"))
        .andExpect(jsonPath("$.receipt").value("subject-1"))
        .andRespond(
            withSuccess(
                "{\"id\":\"order_9\",\"status\":\"created\",\"amount\":49900,\"currency\":\"INR\"}",
                MediaType.APPLICATION_JSON));

    var order = gateway.createOrder("subject-1", 49900, "inr");

    assertThat(order.id()).isEqualTo("order_9");
    assertThat(order.status()).isEqualTo("created");
    server.verify();
  }

  @Test
  @DisplayName("capturedPayment picks the captured one and ignores failed attempts")
  void capturedPayment() {
    server
        .expect(requestTo("https://api.razorpay.com/v1/orders/order_9/payments"))
        .andRespond(
            withSuccess(
                "{\"items\":[{\"id\":\"pay_a\",\"status\":\"failed\",\"amount\":100,\"currency\":\"INR\"},"
                    + "{\"id\":\"pay_b\",\"status\":\"captured\",\"amount\":49900,\"currency\":\"INR\"}]}",
                MediaType.APPLICATION_JSON));

    assertThat(gateway.capturedPayment("order_9").id()).isEqualTo("pay_b");
  }

  @Test
  @DisplayName("refund sends the idempotency header and returns the refund id")
  void refund() {
    server
        .expect(requestTo("https://api.razorpay.com/v1/payments/pay_b/refund"))
        .andExpect(header("X-Refund-Idempotency", "key-1"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.amount").value(1000))
        .andRespond(withSuccess("{\"id\":\"rfnd_1\"}", MediaType.APPLICATION_JSON));

    assertThat(gateway.refund("pay_b", 1000, "key-1", "damaged")).isEqualTo("rfnd_1");
  }

  @Test
  @DisplayName("an upstream error becomes a 503 CodedException without leaking credentials")
  void upstreamError() {
    server
        .expect(requestTo("https://api.razorpay.com/v1/orders/order_9"))
        .andRespond(withServerError());

    assertThatThrownBy(() -> gateway.fetchOrder("order_9"))
        .isInstanceOf(CodedException.class)
        .hasMessageNotContaining("secret");
  }
}
