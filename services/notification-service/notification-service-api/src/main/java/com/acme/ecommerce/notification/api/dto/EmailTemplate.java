package com.acme.ecommerce.notification.api.dto;

/** Transactional email templates rendered by notification-service. */
public enum EmailTemplate {
  /** data: {@code link}, {@code name}. */
  PASSWORD_RESET,
  /** data: {@code link}, {@code name}. */
  EMAIL_VERIFICATION,
  /** data: {@code code}, {@code purpose}, {@code minutes}. */
  OTP,
  /** data: name, productName, price, currency, oldPrice, link. */
  ALERT_PRICE_DROP,
  /** data: name, productName, link. */
  ALERT_BACK_IN_STOCK,
  /** data: name, code, amount, currency, message, from, link. */
  GIFT_CARD,
  /** data: name, items (newline separated), link. */
  CART_REMINDER,
  /** data: name, productName, link. */
  SUBSCRIPTION_ORDER_PLACED,
  /** data: name, productName, reason, link. */
  SUBSCRIPTION_FAILED,
  /** data: name, plan, endsAt, link. */
  MEMBERSHIP_WELCOME,
  /** data: name, link. */
  MEMBERSHIP_EXPIRED,
  /** data: name, coins, link. */
  REFERRAL_REWARD,
  /** data: name, storeName, link. */
  SELLER_APPROVED,
  /** data: name, storeName, reason, link. */
  SELLER_REJECTED
}
