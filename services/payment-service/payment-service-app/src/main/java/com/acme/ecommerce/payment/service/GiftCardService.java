package com.acme.ecommerce.payment.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.client.NotificationFeignClient;
import com.acme.ecommerce.payment.client.NotificationFeignClient.Email;
import com.acme.ecommerce.payment.service.WalletService.Balance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Gift cards: purchase (paid by a GIFT_CARD-purpose CARD payment) and redemption into the wallet. */
@Service
public class GiftCardService {

  private static final Logger log = LoggerFactory.getLogger(GiftCardService.class);
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  public record GiftCardView(
      UUID id,
      String code,
      BigDecimal amount,
      String currency,
      String status,
      String recipientEmail,
      String message,
      Instant redeemedAt,
      Instant createdAt,
      Instant expiresAt) {}

  public record Pending(UUID id, BigDecimal amount, String currency) {}

  private static final String COLS =
      "id, code, amount, currency, status, recipient_email, message, redeemed_at, created_at, "
          + "expires_at";

  private final JdbcClient jdbc;
  private final WalletService wallet;
  private final NotificationFeignClient notifications;
  private final TransactionTemplate tx;

  public GiftCardService(
      JdbcClient jdbc,
      WalletService wallet,
      NotificationFeignClient notifications,
      TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.wallet = wallet;
    this.notifications = notifications;
    this.tx = tx;
  }

  /** Validates and stores a PENDING_PAYMENT card; the caller then creates the payment. */
  public Pending createPending(
      UUID userId,
      String purchaserEmail,
      BigDecimal rawAmount,
      String rawCurrency,
      String recipientEmail,
      String message) {
    String currency = rawCurrency.toUpperCase(Locale.ROOT);
    BigDecimal amount = rawAmount.setScale(2, RoundingMode.HALF_UP);
    boolean inr = "INR".equals(currency);
    BigDecimal min = new BigDecimal(inr ? "100" : "5");
    BigDecimal max = new BigDecimal(inr ? "10000" : "500");
    if (amount.compareTo(min) < 0 || amount.compareTo(max) > 0) {
      throw new CodedException(
          ErrorCode.VALIDATION_FAILED,
          "Gift card amount must be between " + min + " and " + max + " " + currency);
    }
    for (int attempt = 0; attempt < 5; attempt++) {
      UUID id = UUID.randomUUID();
      int n =
          jdbc.sql(
                  "INSERT INTO payment.gift_cards (id, code, amount, currency, status, "
                      + "purchaser_user_id, purchaser_email, recipient_email, message) "
                      + "VALUES (:id, :code, :a, :c, 'PENDING_PAYMENT', :u, :pe, :re, :m) "
                      + "ON CONFLICT (code) DO NOTHING")
              .param("id", id)
              .param("code", newCode())
              .param("a", amount)
              .param("c", currency)
              .param("u", userId)
              .param("pe", purchaserEmail)
              .param("re", recipientEmail.trim())
              .param("m", message == null || message.isBlank() ? null : message.trim())
              .update();
      if (n > 0) {
        return new Pending(id, amount, currency);
      }
    }
    throw new CodedException(ErrorCode.INTERNAL_ERROR, "Could not allocate a gift card code");
  }

  public String statusOf(UUID id) {
    return jdbc.sql("SELECT status FROM payment.gift_cards WHERE id = :i")
        .param("i", id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** Payment outcome for a GIFT_CARD payment. Idempotent; email is best effort. */
  public void onPaymentResult(UUID giftCardId, String outcome) {
    if ("SUCCEEDED".equals(outcome)) {
      int n =
          jdbc.sql(
                  "UPDATE payment.gift_cards SET status = 'ACTIVE' "
                      + "WHERE id = :i AND status = 'PENDING_PAYMENT'")
              .param("i", giftCardId)
              .update();
      if (n > 0) {
        sendEmail(giftCardId);
        notifyPurchaser(giftCardId);
      }
    } else if ("FAILED".equals(outcome)) {
      jdbc.sql(
              "UPDATE payment.gift_cards SET status = 'CANCELLED' "
                  + "WHERE id = :i AND status = 'PENDING_PAYMENT'")
          .param("i", giftCardId)
          .update();
    }
  }

  /** Inbox entry for the purchaser once the card is paid; best effort. */
  private void notifyPurchaser(UUID id) {
    try {
      jdbc.sql(
              "SELECT purchaser_user_id, amount, currency, recipient_email "
                  + "FROM payment.gift_cards WHERE id = :i")
          .param("i", id)
          .query(
              (rs, n) -> {
                notifications.inbox(
                    new NotificationFeignClient.Inbox(
                        rs.getString("purchaser_user_id"),
                        "ACCOUNT",
                        "Gift card sent",
                        rs.getBigDecimal("amount").toPlainString()
                            + " "
                            + rs.getString("currency").trim()
                            + " gift card emailed to "
                            + rs.getString("recipient_email")
                            + ".",
                        "/account/gift-cards"));
                return Boolean.TRUE;
              })
          .optional();
    } catch (RuntimeException e) {
      log.warn("Could not post gift card inbox entry for {}: {}", id, e.toString());
    }
  }

  private void sendEmail(UUID id) {
    try {
      Map<String, String> data =
          jdbc.sql(
                  "SELECT code, amount, currency, recipient_email, message, purchaser_email, "
                      + "expires_at FROM payment.gift_cards WHERE id = :i")
              .param("i", id)
              .query(
                  (rs, n) -> {
                    Map<String, String> d = new java.util.LinkedHashMap<>();
                    d.put("to", rs.getString("recipient_email"));
                    d.put("code", rs.getString("code"));
                    d.put("amount", rs.getBigDecimal("amount").toPlainString());
                    d.put("currency", rs.getString("currency").trim());
                    d.put("message", rs.getString("message") == null ? "" : rs.getString("message"));
                    d.put(
                        "from",
                        rs.getString("purchaser_email") == null
                            ? ""
                            : rs.getString("purchaser_email"));
                    d.put("expiresAt", rs.getTimestamp("expires_at").toInstant().toString());
                    d.put("link", "/account/wallet");
                    return d;
                  })
              .single();
      String to = data.remove("to");
      data.values().removeIf(String::isEmpty);
      notifications.email(new Email(to, "GIFT_CARD", data));
      log.info("Gift card {} email dispatched", id);
    } catch (RuntimeException e) {
      log.warn("Could not send gift card email for {}: {}", id, e.toString());
    }
  }

  /** Single-use redemption into the wallet; all failures are 422 GIFT_CARD_INVALID. */
  public Balance redeem(UUID userId, String rawCode) {
    String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    return tx.execute(
        status -> {
          var row =
              jdbc.sql(
                      "SELECT id, amount, currency, status, expires_at FROM payment.gift_cards "
                          + "WHERE code = :c FOR UPDATE")
                  .param("c", code)
                  .query(
                      (rs, n) ->
                          new Object[] {
                            rs.getObject("id", UUID.class),
                            rs.getBigDecimal("amount"),
                            rs.getString("currency"),
                            rs.getString("status"),
                            rs.getTimestamp("expires_at").toInstant()
                          })
                  .optional()
                  .orElse(null);
          if (row == null
              || !"ACTIVE".equals(row[3])
              || ((Instant) row[4]).isBefore(Instant.now())) {
            throw new CodedException(
                ErrorCode.GIFT_CARD_INVALID, "This gift card code is invalid or already used");
          }
          UUID id = (UUID) row[0];
          Balance b =
              wallet.credit(
                  userId,
                  (BigDecimal) row[1],
                  (String) row[2],
                  "GIFT_CARD",
                  id.toString(),
                  "giftcard-" + id);
          jdbc.sql(
                  "UPDATE payment.gift_cards SET status = 'REDEEMED', redeemed_by = :u, "
                      + "redeemed_at = now() WHERE id = :i")
              .param("u", userId)
              .param("i", id)
              .update();
          return b;
        });
  }

  public List<GiftCardView> listPurchased(UUID userId) {
    return jdbc.sql(
            "SELECT "
                + COLS
                + " FROM payment.gift_cards WHERE purchaser_user_id = :u "
                + "AND status <> 'CANCELLED' ORDER BY created_at DESC LIMIT 100")
        .param("u", userId)
        .query(GiftCardService::map)
        .list();
  }

  public GiftCardView get(UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM payment.gift_cards WHERE id = :i")
        .param("i", id)
        .query(GiftCardService::map)
        .single();
  }

  private static GiftCardView map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    var redeemed = rs.getTimestamp("redeemed_at");
    return new GiftCardView(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getBigDecimal("amount"),
        rs.getString("currency").trim(),
        rs.getString("status"),
        rs.getString("recipient_email"),
        rs.getString("message"),
        redeemed == null ? null : redeemed.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant());
  }

  private static String newCode() {
    StringBuilder sb = new StringBuilder(19);
    for (int i = 0; i < 16; i++) {
      if (i > 0 && i % 4 == 0) {
        sb.append('-');
      }
      sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }
}
