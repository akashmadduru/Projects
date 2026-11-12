package com.acme.ecommerce.notification.api.dto;

/** Transactional email templates rendered by notification-service. */
public enum EmailTemplate {
  /** data: {@code link}, {@code name}. */
  PASSWORD_RESET,
  /** data: {@code link}, {@code name}. */
  EMAIL_VERIFICATION,
  /** data: {@code code}, {@code purpose}, {@code minutes}. */
  OTP
}
