package com.acme.ecommerce.payment.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.payment.client.NotificationFeignClient;
import com.acme.ecommerce.payment.client.NotificationFeignClient.Email;
import com.acme.ecommerce.payment.service.WalletService.Balance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gift cards: purchase (paid by a GIFT_CARD-purpose CARD payment) and redemption into the wallet.
 *
 * <p>Codes are never stored (TD-P8-06). A code is generated when the card is paid for, emailed to
 * the recipient, and kept only as a SHA-256 hash (for redemption) plus its last 4 characters (for
 * display). The purchaser can ask for a resend, which issues a new code and invalidates the old
 * one. Failed redemptions are counted per user and lock redemption for a while.
 */
@Service
public class GiftCardService {

  private static final Logger log = LoggerFactory.getLogger(GiftCardService.class);
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Failed redeem attempts allowed per user inside {@link #LOCKOUT_WINDOW_MINUTES}. */
  static final int MAX_FAILED_REDEEMS = 5;

  static final int LOCKOUT_WINDOW_MINUTES = 15;

  /** A resend mails the recipient, so it is limited to stop it being used to spam an address. */
  static final int RESEND_INTERVAL_MINUTES = 10;

  /** Unpaid cards older than this are cancelled by the sweep. A later payment reactivates them. */
  static final int PENDING_TTL_HOURS = 24;

  public record GiftCardView(
      UUID id,
      /* Masked, for example "****-****-****-7KQ2"; null until the card is paid for. */
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
      "id, code_last4, amount, currency, status, recipient_email, message, redeemed_at,"
          + " created_at, expires_at";

  private final JdbcClient jdbc;
  private final WalletService wallet;
  private final NotificationFeignClient notifications;
  private final TransactionTemplate tx;
  private final JobLock jobLock;

  public GiftCardService(
      JdbcClient jdbc,
      WalletService wallet,
      NotificationFeignClient notifications,
      TransactionTemplate tx,
      JobLock jobLock) {
    this.jdbc = jdbc;
    this.wallet = wallet;
    this.notifications = notifications;
    this.tx = tx;
    this.jobLock = jobLock;
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
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO payment.gift_cards (id, amount, currency, status, purchaser_user_id,"
                + " purchaser_email, recipient_email, message)"
                + " VALUES (:id, :a, :c, 'PENDING_PAYMENT', :u, :pe, :re, :m)")
        .param("id", id)
        .param("a", amount)
        .param("c", currency)
        .param("u", userId)
        .param("pe", purchaserEmail)
        .param("re", recipientEmail.trim())
        .param("m", message == null || message.isBlank() ? null : message.trim())
        .update();
    return new Pending(id, amount, currency);
  }

  public String statusOf(UUID id) {
    return jdbc.sql("SELECT status FROM payment.gift_cards WHERE id = :i")
        .param("i", id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /**
   * Payment outcome for a GIFT_CARD payment. Idempotent. Success also activates a card the sweep
   * cancelled: the money arrived, so the customer must get the card.
   */
  public void onPaymentResult(UUID giftCardId, String outcome) {
    if ("SUCCEEDED".equals(outcome)) {
      String code = issueCode(giftCardId, "status IN ('PENDING_PAYMENT', 'CANCELLED')", null);
      if (code != null) {
        jdbc.sql("UPDATE payment.gift_cards SET status = 'ACTIVE' WHERE id = :i")
            .param("i", giftCardId)
            .update();
        sendEmail(giftCardId, code);
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

  /**
   * Purchaser asks for the recipient email again. A new code is issued (the old one stops working),
   * because the old one was never stored and cannot be re-sent.
   */
  public void resend(UUID userId, UUID giftCardId) {
    String code =
        issueCode(
            giftCardId,
            "status = 'ACTIVE' AND purchaser_user_id = :u AND (code_issued_at IS NULL"
                + " OR code_issued_at < now() - make_interval(mins => "
                + RESEND_INTERVAL_MINUTES
                + "))",
            userId);
    if (code == null) {
      String status =
          jdbc.sql("SELECT status FROM payment.gift_cards WHERE id = :i AND purchaser_user_id = :u")
              .param("i", giftCardId)
              .param("u", userId)
              .query(String.class)
              .optional()
              .orElseThrow(
                  () -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Gift card not found"));
      if ("ACTIVE".equals(status)) {
        throw new CodedException(
            ErrorCode.RATE_LIMITED,
            "A new code was sent recently. Try again in " + RESEND_INTERVAL_MINUTES + " minutes.");
      }
      throw new CodedException(
          ErrorCode.RESOURCE_CONFLICT, "Only an unused, paid gift card can be re-sent");
    }
    sendEmail(giftCardId, code);
  }

  /**
   * Stores the hash of a fresh code on the card if {@code condition} holds. Returns the plaintext
   * code, or null if the card did not match. Retries on the (astronomically unlikely) collision.
   */
  private String issueCode(UUID id, String condition, UUID userId) {
    for (int attempt = 0; attempt < 5; attempt++) {
      String code = newCode();
      try {
        var spec =
            jdbc.sql(
                    "UPDATE payment.gift_cards SET code_hash = :h, code_last4 = :l,"
                        + " code_issued_at = now() WHERE id = :i AND "
                        + condition)
                .param("h", hash(code))
                .param("l", code.substring(code.length() - 4))
                .param("i", id);
        if (userId != null) {
          spec = spec.param("u", userId);
        }
        return spec.update() > 0 ? code : null;
      } catch (DuplicateKeyException e) {
        log.warn("Gift card code collision on attempt {}; retrying", attempt + 1);
      }
    }
    throw new CodedException(ErrorCode.INTERNAL_ERROR, "Could not allocate a gift card code");
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

  /** Best effort; if it fails the purchaser can resend (which issues a new code). */
  private void sendEmail(UUID id, String code) {
    try {
      Map<String, String> data =
          jdbc.sql(
                  "SELECT amount, currency, recipient_email, message, purchaser_email, "
                      + "expires_at FROM payment.gift_cards WHERE id = :i")
              .param("i", id)
              .query(
                  (rs, n) -> {
                    Map<String, String> d = new java.util.LinkedHashMap<>();
                    d.put("to", rs.getString("recipient_email"));
                    d.put("code", code);
                    d.put("amount", rs.getBigDecimal("amount").toPlainString());
                    d.put("currency", rs.getString("currency").trim());
                    d.put(
                        "message", rs.getString("message") == null ? "" : rs.getString("message"));
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

  /**
   * Single-use redemption into the wallet; an unknown, used or expired code is 422
   * GIFT_CARD_INVALID. After {@link #MAX_FAILED_REDEEMS} failures in {@link
   * #LOCKOUT_WINDOW_MINUTES} minutes the user gets 429 RATE_LIMITED, even for a valid code.
   */
  public Balance redeem(UUID userId, String rawCode) {
    Integer recentFailures =
        jdbc.sql(
                "SELECT count(*) FROM payment.gift_card_redeem_failures WHERE user_id = :u"
                    + " AND failed_at > now() - make_interval(mins => "
                    + LOCKOUT_WINDOW_MINUTES
                    + ")")
            .param("u", userId)
            .query(Integer.class)
            .single();
    if (recentFailures != null && recentFailures >= MAX_FAILED_REDEEMS) {
      throw new CodedException(
          ErrorCode.RATE_LIMITED,
          "Too many invalid gift card codes. Try again in " + LOCKOUT_WINDOW_MINUTES + " minutes.");
    }
    String code = canonical(rawCode);
    try {
      return tx.execute(status -> redeemLocked(userId, code));
    } catch (CodedException e) {
      if (e.errorCode() == ErrorCode.GIFT_CARD_INVALID) {
        // Outside the redeem transaction, which has rolled back.
        jdbc.sql("INSERT INTO payment.gift_card_redeem_failures (user_id) VALUES (:u)")
            .param("u", userId)
            .update();
      }
      throw e;
    }
  }

  private Balance redeemLocked(UUID userId, String code) {
    var row =
        code == null
            ? null
            : jdbc.sql(
                    "SELECT id, amount, currency, status, expires_at FROM payment.gift_cards "
                        + "WHERE code_hash = :h FOR UPDATE")
                .param("h", hash(code))
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
    if (row == null || !"ACTIVE".equals(row[3]) || ((Instant) row[4]).isBefore(Instant.now())) {
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
  }

  /** Hourly: cancel abandoned unpaid cards and forget old redeem failures. One replica only. */
  @Scheduled(cron = "${payment.gift-cards.sweep-cron:0 17 * * * *}")
  public void sweep() {
    jobLock.runExclusive("payment-gift-card-sweep", this::sweepLocked);
  }

  void sweepLocked() {
    int cancelled =
        jdbc.sql(
                "UPDATE payment.gift_cards SET status = 'CANCELLED' WHERE status ="
                    + " 'PENDING_PAYMENT' AND created_at < now() - make_interval(hours => "
                    + PENDING_TTL_HOURS
                    + ")")
            .update();
    int forgotten =
        jdbc.sql(
                "DELETE FROM payment.gift_card_redeem_failures"
                    + " WHERE failed_at < now() - interval '1 day'")
            .update();
    if (cancelled > 0 || forgotten > 0) {
      log.info(
          "Gift card sweep: cancelled {} unpaid card(s), forgot {} redeem failure(s)",
          cancelled,
          forgotten);
    }
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
    String last4 = rs.getString("code_last4");
    return new GiftCardView(
        rs.getObject("id", UUID.class),
        last4 == null ? null : "****-****-****-" + last4,
        rs.getBigDecimal("amount"),
        rs.getString("currency").trim(),
        rs.getString("status"),
        rs.getString("recipient_email"),
        rs.getString("message"),
        redeemed == null ? null : redeemed.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant());
  }

  /**
   * The stored form: upper case, grouped XXXX-XXXX-XXXX-XXXX. Accepts the code typed with or
   * without dashes and spaces. Null if it cannot be a code.
   */
  static String canonical(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    if (s.length() != 16) {
      return null;
    }
    return s.substring(0, 4)
        + "-"
        + s.substring(4, 8)
        + "-"
        + s.substring(8, 12)
        + "-"
        + s.substring(12);
  }

  static String hash(String code) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
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
