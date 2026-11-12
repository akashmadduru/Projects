package com.acme.ecommerce.auth.service;

import java.util.Optional;
import java.util.regex.Pattern;

/** E.164 phone normalisation shared by phone verification and OTP login. */
final class PhoneNumbers {

  private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

  private PhoneNumbers() {}

  /** Strips spaces, dashes and brackets; returns the number only if it is valid E.164. */
  static Optional<String> normalise(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String cleaned = raw.replaceAll("[\\s\\-().]", "");
    return E164.matcher(cleaned).matches() ? Optional.of(cleaned) : Optional.empty();
  }
}
