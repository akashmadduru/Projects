package com.acme.ecommerce.order.web;

import java.time.Instant;
import java.time.OffsetDateTime;

/** Native-query timestamp normaliser. */
final class Time {
  private Time() {}

  static Instant instant(Object o) {
    if (o instanceof Instant i) {
      return i;
    }
    if (o instanceof OffsetDateTime odt) {
      return odt.toInstant();
    }
    if (o instanceof java.util.Date d) {
      return d.toInstant();
    }
    return null;
  }
}
