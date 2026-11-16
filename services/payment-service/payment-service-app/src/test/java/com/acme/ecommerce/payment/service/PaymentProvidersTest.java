package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.payment.config.PaymentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * SIMULATED mode lets a customer mark their own card payment as paid, so it must be impossible to
 * reach in production; and several providers can be on at once.
 */
class PaymentProvidersTest {

  private static PaymentProperties props(String stripeKey, String rzpId, String rzpSecret) {
    PaymentProperties p = new PaymentProperties();
    p.getStripe().setSecretKey(stripeKey);
    p.getRazorpay().setKeyId(rzpId);
    p.getRazorpay().setKeySecret(rzpSecret);
    return p;
  }

  private static MockEnvironment profile(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @Test
  @DisplayName("prod with no provider at all refuses to start")
  void prodWithoutProviderFailsClosed() {
    PaymentProviders providers = new PaymentProviders(props("", "", ""), profile("prod"));

    assertThatThrownBy(providers::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STRIPE_SECRET_KEY")
        .hasMessageContaining("RAZORPAY_KEY_ID")
        .hasMessageContaining("SIMULATED");
  }

  @Test
  @DisplayName("whitespace keys count as not configured")
  void whitespaceKeysAreNotConfigured() {
    PaymentProviders providers = new PaymentProviders(props("   ", " ", " "), profile("prod"));

    assertThatThrownBy(providers::init).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("a Razorpay key id without its secret is not enough")
  void razorpayNeedsBothKeys() {
    PaymentProviders providers = new PaymentProviders(props("", "rzp_live_x", ""), profile("prod"));

    assertThat(providers.enabled()).isEmpty();
    assertThatThrownBy(providers::init).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("prod with only Razorpay starts")
  void prodWithOnlyRazorpayStarts() {
    PaymentProviders providers = new PaymentProviders(props("", "id", "secret"), profile("prod"));

    assertThatCode(providers::init).doesNotThrowAnyException();
    assertThat(providers.enabled()).containsExactly("RAZORPAY");
    assertThat(providers.defaultProvider()).isEqualTo("RAZORPAY");
  }

  @Test
  @DisplayName("without prod and without keys it runs SIMULATED")
  void devWithoutKeysIsSimulated() {
    PaymentProviders providers = new PaymentProviders(props("", "", ""), profile("local"));

    assertThatCode(providers::init).doesNotThrowAnyException();
    assertThat(providers.anyEnabled()).isFalse();
    assertThat(providers.defaultProvider()).isEqualTo("SIMULATED");
  }

  @Test
  @DisplayName("both providers on: stable order, configurable default")
  void bothProviders() {
    PaymentProperties p = props("sk_test_x", "id", "secret");
    PaymentProviders providers = new PaymentProviders(p, profile("prod"));

    assertThat(providers.enabled()).containsExactly("STRIPE", "RAZORPAY");
    assertThat(providers.defaultProvider()).isEqualTo("STRIPE");

    p.setDefaultProvider("razorpay");
    assertThat(providers.defaultProvider()).isEqualTo("RAZORPAY");

    p.setDefaultProvider("PAYPAL");
    assertThat(providers.defaultProvider()).isEqualTo("STRIPE");
  }

  @Test
  @DisplayName("resolve refuses a provider that is not switched on")
  void resolveRefusesDisabled() {
    PaymentProviders providers = new PaymentProviders(props("sk", "", ""), profile("prod"));

    assertThat(providers.resolve(null)).isEqualTo("STRIPE");
    assertThat(providers.resolve("stripe")).isEqualTo("STRIPE");
    assertThatThrownBy(() -> providers.resolve("RAZORPAY")).isInstanceOf(CodedException.class);
  }
}
