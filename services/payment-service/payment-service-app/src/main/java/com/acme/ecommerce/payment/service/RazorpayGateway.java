package com.acme.ecommerce.payment.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.config.PaymentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin wrapper over the Razorpay REST API (Orders, Payments, Refunds) so the rest of the service
 * never sees HTTP. Amounts are in paise. Signature helpers are static and side-effect free.
 */
@Component
public class RazorpayGateway {

  private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

  /** Order snapshot; status is created, attempted or paid. */
  public record Order(String id, String status, long amount, String currency) {}

  /** The captured payment of an order, if any. */
  public record CapturedPayment(String id, long amount, String currency) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  private final PaymentProperties properties;
  private final RestClient http;

  /**
   * The constructor Spring uses. Explicit because the class also has a package-private test-seam
   * constructor, and with two constructors Spring will not guess (it falls back to a no-arg one and
   * fails with "No default constructor found").
   */
  @Autowired
  public RazorpayGateway(PaymentProperties properties) {
    this(properties, null);
  }

  /** Test seam: a prebuilt client (for example bound to a mock server). */
  RazorpayGateway(PaymentProperties properties, RestClient client) {
    this.properties = properties;
    PaymentProperties.Razorpay cfg = properties.getRazorpay();
    this.http = client != null ? client : build(cfg);
  }

  private static RestClient build(PaymentProperties.Razorpay cfg) {
    HttpClient jdk = HttpClient.newBuilder().connectTimeout(cfg.getConnectTimeout()).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
    factory.setReadTimeout(cfg.getReadTimeout());
    RestClient.Builder builder =
        RestClient.builder().baseUrl(cfg.getBaseUrl()).requestFactory(factory);
    if (cfg.enabled()) {
      builder.defaultHeaders(h -> h.setBasicAuth(cfg.getKeyId(), cfg.getKeySecret()));
    }
    return builder.build();
  }

  public boolean enabled() {
    return properties.getRazorpay().enabled();
  }

  public String keyId() {
    return properties.getRazorpay().getKeyId();
  }

  /** Creates the order the browser checkout pays against. {@code receipt} is our subject id. */
  public Order createOrder(String receipt, long paise, String currency) {
    Map<String, Object> body =
        Map.of(
            "amount",
            paise,
            "currency",
            currency.toUpperCase(java.util.Locale.ROOT),
            "receipt",
            receipt,
            "notes",
            Map.of("subjectId", receipt));
    JsonNode n =
        call(
            () ->
                http.post()
                    .uri("/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json(body))
                    .retrieve()
                    .body(String.class));
    return order(n);
  }

  public Order fetchOrder(String orderId) {
    return order(
        call(() -> http.get().uri("/v1/orders/{id}", orderId).retrieve().body(String.class)));
  }

  /** The captured payment of an order, or null when none has been captured. */
  public CapturedPayment capturedPayment(String orderId) {
    JsonNode n =
        call(
            () ->
                http.get().uri("/v1/orders/{id}/payments", orderId).retrieve().body(String.class));
    if (n != null && n.path("items").isArray()) {
      for (JsonNode p : n.get("items")) {
        if ("captured".equals(p.path("status").asText())) {
          return new CapturedPayment(
              p.path("id").asText(), p.path("amount").asLong(), p.path("currency").asText());
        }
      }
    }
    return null;
  }

  /** Full refund of {@code paise} against a captured payment; returns the Razorpay refund id. */
  public String refund(String paymentId, long paise, String idempotencyKey, String reason) {
    Map<String, Object> body =
        Map.of(
            "amount", paise,
            "receipt", idempotencyKey,
            "notes", Map.of("reason", reason == null ? "" : reason));
    JsonNode n =
        call(
            () ->
                http.post()
                    .uri("/v1/payments/{id}/refund", paymentId)
                    .header("X-Refund-Idempotency", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json(body))
                    .retrieve()
                    .body(String.class));
    return n == null ? null : n.path("id").asText(null);
  }

  /** Checkout success: HMAC-SHA256(orderId + "|" + paymentId) with the key secret, hex. */
  public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
    return matches(
        hmacHex(properties.getRazorpay().getKeySecret(), orderId + "|" + paymentId), signature);
  }

  /** Webhook: HMAC-SHA256(raw body) with the webhook secret, hex. */
  public boolean verifyWebhookSignature(String rawBody, String signature) {
    String secret = properties.getRazorpay().getWebhookSecret();
    if (secret == null || secret.isBlank()) {
      return false;
    }
    return matches(hmacHex(secret, rawBody), signature);
  }

  static String hmacHex(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  /** Constant-time compare so a signature cannot be guessed byte by byte. */
  private static boolean matches(String expected, String provided) {
    if (provided == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        provided.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
  }

  private static Order order(JsonNode n) {
    if (n == null || n.path("id").asText("").isBlank()) {
      throw new CodedException(
          ErrorCode.UPSTREAM_UNAVAILABLE, "Payment provider returned no order");
    }
    return new Order(
        n.path("id").asText(),
        n.path("status").asText(),
        n.path("amount").asLong(),
        n.path("currency").asText());
  }

  private static String json(Object body) {
    try {
      return JSON.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialise request", e);
    }
  }

  private static JsonNode call(java.util.function.Supplier<String> request) {
    try {
      String raw = request.get();
      return raw == null || raw.isBlank() ? null : JSON.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new CodedException(
          ErrorCode.UPSTREAM_UNAVAILABLE, "Payment provider sent an unreadable reply", e);
    } catch (RestClientException e) {
      // The message can echo the request URL but never the credentials, which travel in a header.
      log.warn("Razorpay call failed: {}", e.getMessage());
      throw new CodedException(
          ErrorCode.UPSTREAM_UNAVAILABLE, "Payment provider request failed", e);
    }
  }
}
