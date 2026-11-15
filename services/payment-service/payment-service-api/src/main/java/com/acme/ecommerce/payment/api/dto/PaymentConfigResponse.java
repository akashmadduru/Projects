package com.acme.ecommerce.payment.api.dto;

import java.util.List;

/**
 * Public config. {@code providers} lists every online provider switched on (STRIPE, RAZORPAY), the
 * customer picks one at checkout; {@code defaultProvider} is preselected. {@code mode} and {@code
 * publishableKey} describe the default provider and are kept for older clients: mode is
 * STRIPE, RAZORPAY or SIMULATED, publishableKey is null when SIMULATED.
 */
public record PaymentConfigResponse(
    String mode, String publishableKey, List<Provider> providers, String defaultProvider) {

  /**
   * One selectable provider. {@code publicKey} is the browser-safe key (never a secret). {@code
   * currencies} is empty when any currency is accepted, otherwise the only ones it takes.
   */
  public record Provider(
      String id, String label, String publicKey, List<String> methods, List<String> currencies) {}
}
