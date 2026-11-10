package com.acme.ecommerce.common.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemPayloadFactoryTest {

  private static final Instant FIXED_NOW = Instant.parse("2025-06-01T12:00:00Z");

  private ProblemPayloadFactory factory;

  @BeforeEach
  void setUp() {
    factory = new ProblemPayloadFactory(new FixedTimeProvider(FIXED_NOW));
    CorrelationIdHolder.set("test-correlation-id");
  }

  @AfterEach
  void tearDown() {
    CorrelationIdHolder.clear();
  }

  @Test
  @DisplayName("maps a domain exception onto the RFC 9457 members")
  void mapsDomainException() {
    ProblemPayload payload =
        factory.from(new ResourceNotFoundException("Product", "sku-1"), "/api/products/sku-1");

    assertThat(payload.status()).isEqualTo(404);
    assertThat(payload.code()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(payload.type())
        .isEqualTo("https://errors.acme-ecommerce.internal/resource-not-found");
    assertThat(payload.title()).isEqualTo("Resource not found");
    assertThat(payload.detail()).isEqualTo("Product not found: sku-1");
    assertThat(payload.instance()).isEqualTo("/api/products/sku-1");
    assertThat(payload.correlationId()).isEqualTo("test-correlation-id");
    assertThat(payload.timestamp()).isEqualTo(FIXED_NOW);
    assertThat(payload.extensions())
        .containsEntry("resourceType", "Product")
        .containsEntry("identifier", "sku-1");
  }

  @Test
  @DisplayName("never leaks an exception message on a 5xx response")
  void suppressesDetailForServerErrors() {
    // This is the security-relevant behaviour: internal messages routinely contain SQL fragments
    // and hostnames, and must never reach the client.
    ProblemPayload payload =
        factory.from(
            new UpstreamUnavailableException(
                "product-service", new IllegalStateException("jdbc://secret-host:5432 timed out")),
            "/api/orders");

    assertThat(payload.status()).isEqualTo(503);
    assertThat(payload.detail()).doesNotContain("secret-host").doesNotContain("jdbc");
    assertThat(payload.detail()).contains("correlationId");
  }

  @Test
  @DisplayName("internalError produces an opaque 500 with a correlation id")
  void internalErrorIsOpaque() {
    ProblemPayload payload = factory.internalError("/api/anything");

    assertThat(payload.status()).isEqualTo(500);
    assertThat(payload.code()).isEqualTo("INTERNAL_ERROR");
    assertThat(payload.correlationId()).isEqualTo("test-correlation-id");
  }

  @Test
  @DisplayName("falls back to the error title when no detail is supplied")
  void fallsBackToTitle() {
    ProblemPayload payload = factory.create(ErrorCode.FORBIDDEN, null, "/api/admin");

    assertThat(payload.detail()).isEqualTo("Forbidden");
  }

  @Test
  @DisplayName("generates a correlation id when none is bound")
  void generatesCorrelationIdWhenAbsent() {
    CorrelationIdHolder.clear();

    ProblemPayload payload = factory.create(ErrorCode.VALIDATION_FAILED, "bad", "/api/x");

    assertThat(payload.correlationId()).isNotBlank();
  }

  @Test
  @DisplayName("extension members are carried through and immutable")
  void extensionsAreImmutable() {
    ProblemPayload payload =
        factory.create(ErrorCode.VALIDATION_FAILED, "bad", "/api/x", Map.of("field", "name"));

    assertThat(payload.extensions()).containsEntry("field", "name");
    assertThat(payload.extensions().getClass().getName()).doesNotContain("Mutable");
  }
}
