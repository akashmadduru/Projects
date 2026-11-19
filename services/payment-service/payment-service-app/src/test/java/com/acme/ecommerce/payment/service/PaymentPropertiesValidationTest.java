package com.acme.ecommerce.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Startup validation of the Razorpay claim and watch settings. */
class PaymentPropertiesValidationTest {

  @Test
  @DisplayName("the defaults are valid")
  void defaultsAreValid() {
    assertThatCode(() -> new PaymentProperties().validate()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a lease not longer than connect + read timeout fails startup")
  void leaseMustExceedTimeouts() {
    var p = new PaymentProperties();
    p.getRazorpay().setProvisionLease(Duration.ofSeconds(13));

    assertThatThrownBy(p::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provision-lease");
  }

  @Test
  @DisplayName("a non-positive watch interval or alert delay fails startup")
  void watchSettingsMustBePositive() {
    var a = new PaymentProperties();
    a.getRazorpay().setWatchIntervalMs(0);
    var b = new PaymentProperties();
    b.getRazorpay().setCaptureAlertAfter(Duration.ZERO);
    var c = new PaymentProperties();
    c.getRazorpay().setRefundPendingAlertAfter(Duration.ofDays(-1));

    assertThat(new PaymentProperties().getRazorpay().getWatchIntervalMs()).isPositive();
    for (var p : new PaymentProperties[] {a, b, c}) {
      assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }
  }
}
