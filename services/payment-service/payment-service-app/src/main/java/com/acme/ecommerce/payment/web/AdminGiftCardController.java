package com.acme.ecommerce.payment.web;

import com.acme.ecommerce.common.core.error.ValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Support lookup of gift cards by the last 4 characters of the code and/or an email (purchaser or
 * recipient), TD-P8-21. The full code is never stored, so it is never shown. Read-only; under
 * {@code /api/admin/payments} so the existing gateway route and audit rules apply.
 */
@RestController
@RequestMapping("/api/admin/payments/gift-cards")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE','SUPPORT')")
public class AdminGiftCardController {

  private static final int LIMIT = 50;

  public record GiftCardRow(
      UUID id,
      String last4,
      BigDecimal amount,
      String currency,
      String status,
      String purchaserEmail,
      String recipientEmail,
      UUID redeemedBy,
      Instant redeemedAt,
      Instant createdAt,
      Instant expiresAt) {}

  private final JdbcClient jdbc;

  public AdminGiftCardController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping
  public List<GiftCardRow> search(
      @RequestParam(required = false) String last4, @RequestParam(required = false) String email) {
    String l4 = last4 == null ? "" : last4.trim().toUpperCase(Locale.ROOT);
    String em = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (l4.isEmpty() && em.isEmpty()) {
      throw new ValidationException("Give last4 or email");
    }
    if (!l4.isEmpty() && !l4.matches("[A-Z0-9]{4}")) {
      throw new ValidationException("last4 must be 4 letters or digits");
    }
    return jdbc.sql(
            "SELECT id, code_last4, amount, currency, status, purchaser_email, recipient_email,"
                + " redeemed_by, redeemed_at, created_at, expires_at FROM payment.gift_cards"
                + " WHERE (:l4 = '' OR code_last4 = :l4)"
                + " AND (:em = '' OR lower(recipient_email) = :em OR lower(purchaser_email) = :em)"
                + " ORDER BY created_at DESC LIMIT "
                + LIMIT)
        .param("l4", l4)
        .param("em", em)
        .query(
            (rs, n) ->
                new GiftCardRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("code_last4"),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency"),
                    rs.getString("status"),
                    rs.getString("purchaser_email"),
                    rs.getString("recipient_email"),
                    rs.getObject("redeemed_by", UUID.class),
                    instant(rs.getObject("redeemed_at", OffsetDateTime.class)),
                    instant(rs.getObject("created_at", OffsetDateTime.class)),
                    instant(rs.getObject("expires_at", OffsetDateTime.class))))
        .list();
  }

  private static Instant instant(OffsetDateTime t) {
    return t == null ? null : t.toInstant();
  }
}
