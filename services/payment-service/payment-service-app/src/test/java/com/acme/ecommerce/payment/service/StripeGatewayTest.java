package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.payment.config.PaymentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * SIMULATED mode lets a customer mark their own card payment as paid, so it must be impossible to
 * reach in production.
 */
class StripeGatewayTest {

  private static PaymentProperties withKey(String secretKey) {
    PaymentProperties properties = new PaymentProperties();
    properties.getStripe().setSecretKey(secretKey);
    return properties;
  }

  private static MockEnvironment profile(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @Test
  @DisplayName("prod profile without a Stripe key refuses to start")
  void prodWithoutKeyFailsClosed() {
    StripeGateway gateway = new StripeGateway(withKey(""), profile("prod"));

    assertThatThrownBy(gateway::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STRIPE_SECRET_KEY is required")
        .hasMessageContaining("SIMULATED");
  }

  @Test
  @DisplayName("prod profile with a blank (whitespace) key also refuses to start")
  void prodWithWhitespaceKeyFailsClosed() {
    StripeGateway gateway = new StripeGateway(withKey("   "), profile("prod"));

    assertThatThrownBy(gateway::init).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("prod profile with a key starts in STRIPE mode")
  void prodWithKeyStarts() {
    StripeGateway gateway = new StripeGateway(withKey("sk_test_dummy"), profile("prod"));

    assertThatCode(gateway::init).doesNotThrowAnyException();
    assertThat(gateway.enabled()).isTrue();
    assertThat(gateway.mode()).isEqualTo("STRIPE");
  }

  @Test
  @DisplayName("dev (no profile) without a key still falls back to SIMULATED for local work")
  void devWithoutKeyIsSimulated() {
    StripeGateway gateway = new StripeGateway(withKey(""), profile());

    assertThatCode(gateway::init).doesNotThrowAnyException();
    assertThat(gateway.enabled()).isFalse();
    assertThat(gateway.mode()).isEqualTo("SIMULATED");
  }
}
