package com.acme.ecommerce.user.privacy;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.user.membership.Notifier;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Privacy orchestration: data export jobs (zip kept 24 h) and account deletion (7-day grace, then
 * USER_DELETED on {@code user.events}; every service anonymises its own data).
 */
@Service
public class PrivacyService {

  private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

  static final String TOPIC = "user.events";
  static final List<String> EXPORT_SERVICES =
      List.of(
          "auth-service",
          "cart-service",
          "order-service",
          "payment-service",
          "promotion-service",
          "rating-service",
          "notification-service",
          "support-service",
          "seller-service");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final DateTimeFormatter HUMAN =
      DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'");

  public record ExportJob(
      UUID jobId, String status, String downloadUrl, OffsetDateTime expiresAt) {}

  public record DeletionState(boolean scheduled, OffsetDateTime scheduledFor) {}

  private final JdbcClient jdbc;
  private final PrivacyKafkaConfig.PrivacyHttp http;
  private final Notifier notifier;
  private final KafkaTemplate<String, String> kafka;
  private final String token;
  private final String storefront;
  private final int graceDays;
  private final int exportTtlHours;

  public PrivacyService(
      JdbcClient jdbc,
      PrivacyKafkaConfig.PrivacyHttp http,
      Notifier notifier,
      KafkaTemplate<String, String> privacyKafkaTemplate,
      @Value("${user.internal-token:}") String token,
      @Value("${user.storefront-url:http://localhost:3000}") String storefront,
      @Value("${user.privacy.grace-days:7}") int graceDays,
      @Value("${user.privacy.export-ttl-hours:24}") int exportTtlHours) {
    this.jdbc = jdbc;
    this.http = http;
    this.notifier = notifier;
    this.kafka = privacyKafkaTemplate;
    this.token = token;
    this.storefront = storefront;
    this.graceDays = graceDays;
    this.exportTtlHours = exportTtlHours;
  }

  // ------------------------------------------------------------------ export

  public ExportJob requestExport(UUID userId, String email) {
    Optional<ExportJob> active =
        jdbc.sql(
                "SELECT id FROM users.export_jobs WHERE user_id = :u AND status IN"
                    + " ('PENDING','RUNNING') AND created_at > now() - interval '10 minutes'"
                    + " ORDER BY created_at DESC LIMIT 1")
            .param("u", userId)
            .query(UUID.class)
            .optional()
            .map(id -> getExport(userId, id));
    if (active.isPresent()) {
      return active.get();
    }
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO users.export_jobs (id, user_id, email) VALUES (:id, :u, :e)")
        .param("id", id)
        .param("u", userId)
        .param("e", email)
        .update();
    Thread.ofVirtual().name("privacy-export-" + id).start(() -> build(id, userId, email));
    return getExport(userId, id);
  }

  public ExportJob getExport(UUID userId, UUID jobId) {
    expireOld();
    return jdbc.sql(
            "SELECT id, status, expires_at FROM users.export_jobs WHERE id = :id AND user_id = :u")
        .param("id", jobId)
        .param("u", userId)
        .query(
            (rs, n) -> {
              String status = rs.getString("status");
              return new ExportJob(
                  rs.getObject("id", UUID.class),
                  status,
                  "READY".equals(status)
                      ? "/api/users/me/export/" + jobId + "/download"
                      : null,
                  rs.getObject("expires_at", OffsetDateTime.class));
            })
        .optional()
        .orElseThrow(() -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Export not found"));
  }

  /** Returns the zip bytes of a READY export owned by the user. */
  public byte[] download(UUID userId, UUID jobId) {
    ExportJob job = getExport(userId, jobId);
    if (!"READY".equals(job.status())) {
      throw new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Export is not available");
    }
    return jdbc.sql("SELECT content FROM users.export_jobs WHERE id = :id AND user_id = :u")
        .param("id", jobId)
        .param("u", userId)
        .query((rs, n) -> rs.getBytes("content"))
        .single();
  }

  @Scheduled(fixedDelay = 3_600_000L, initialDelay = 120_000L)
  public void expireOld() {
    jdbc.sql(
            "UPDATE users.export_jobs SET status = 'EXPIRED', content = NULL WHERE status ="
                + " 'READY' AND expires_at < now()")
        .update();
    jdbc.sql(
            "UPDATE users.export_jobs SET status = 'FAILED', error = 'timed out' WHERE status IN"
                + " ('PENDING','RUNNING') AND created_at < now() - interval '30 minutes'")
        .update();
    jdbc.sql("DELETE FROM users.export_jobs WHERE created_at < now() - interval '7 days'").update();
  }

  private void build(UUID jobId, UUID userId, String email) {
    try {
      jdbc.sql("UPDATE users.export_jobs SET status = 'RUNNING' WHERE id = :id")
          .param("id", jobId)
          .update();
      Map<String, String> sections = new LinkedHashMap<>();
      sections.put("user", ownData(userId));
      for (String svc : EXPORT_SERVICES) {
        sections.put(svc.replace("-service", ""), fetchExport(svc, userId));
      }
      StringBuilder all = new StringBuilder("{\"generatedAt\":\"" + Instant.now() + "\",");
      all.append("\"userId\":\"").append(userId).append("\",\"sections\":{");
      boolean first = true;
      for (Map.Entry<String, String> e : sections.entrySet()) {
        all.append(first ? "" : ",").append('"').append(e.getKey()).append("\":").append(e.getValue());
        first = false;
      }
      all.append("}}");
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
        put(zip, "export.json", all.toString());
        for (Map.Entry<String, String> e : sections.entrySet()) {
          put(zip, "sections/" + e.getKey() + ".json", e.getValue());
        }
        put(
            zip,
            "README.txt",
            "Copy of the personal data held about your account. export.json contains everything;"
                + " sections/ has one file per system. Generated "
                + Instant.now()
                + ".\n");
      }
      byte[] bytes = bos.toByteArray();
      Instant expires = Instant.now().plus(Duration.ofHours(exportTtlHours));
      jdbc.sql(
              "UPDATE users.export_jobs SET status = 'READY', content = :c, size_bytes = :s,"
                  + " completed_at = now(), expires_at = :x WHERE id = :id")
          .param("c", bytes)
          .param("s", (long) bytes.length)
          .param("x", java.sql.Timestamp.from(expires))
          .param("id", jobId)
          .update();
      notifier.email(
          email,
          "PRIVACY_EXPORT_READY",
          Map.of(
              "name", "there",
              "link", storefront + "/account/privacy",
              "expiresAt", HUMAN.format(expires.atOffset(ZoneOffset.UTC))));
    } catch (RuntimeException | java.io.IOException e) {
      log.error("Export {} failed: {}", jobId, e.toString());
      jdbc.sql("UPDATE users.export_jobs SET status = 'FAILED', error = :e WHERE id = :id")
          .param("e", e.getClass().getSimpleName())
          .param("id", jobId)
          .update();
    }
  }

  private static void put(ZipOutputStream zip, String name, String content)
      throws java.io.IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private String agg(String sql, UUID userId) {
    return jdbc.sql(
            "SELECT coalesce(jsonb_agg(to_jsonb(t)), '[]'::jsonb)::text FROM (" + sql + ") t")
        .param("u", userId)
        .query(String.class)
        .single();
  }

  private String ownData(UUID u) {
    return "{\"profile\":"
        + agg("SELECT * FROM users.user_profiles WHERE user_id = :u", u)
        + ",\"addresses\":"
        + agg("SELECT * FROM users.addresses WHERE user_id = :u", u)
        + ",\"preferences\":"
        + agg("SELECT * FROM users.user_preferences WHERE user_id = :u", u)
        + ",\"membership\":"
        + agg("SELECT * FROM users.memberships WHERE user_id = :u", u)
        + ",\"membershipPayments\":"
        + agg("SELECT * FROM users.membership_payments WHERE user_id = :u", u)
        + ",\"referralCode\":"
        + agg("SELECT * FROM users.referral_codes WHERE user_id = :u", u)
        + ",\"referralClaims\":"
        + agg(
            "SELECT * FROM users.referral_claims WHERE referee_user_id = :u OR referrer_user_id ="
                + " :u",
            u)
        + ",\"recentlyViewed\":"
        + agg("SELECT * FROM users.recently_viewed WHERE user_id = :u", u)
        + "}";
  }

  private String fetchExport(String service, UUID userId) {
    try {
      String body =
          http.get("http://" + service + "/internal/privacy/users/{id}/export", token, userId);
      return body == null || body.isBlank() ? "null" : body;
    } catch (RuntimeException e) {
      log.warn("Export section {} unavailable: {}", service, e.toString());
      return "{\"error\":\"section unavailable\"}";
    }
  }

  // ---------------------------------------------------------------- deletion

  public DeletionState deletionState(UUID userId) {
    return jdbc.sql(
            "SELECT scheduled_for FROM users.deletion_requests WHERE user_id = :u AND status ="
                + " 'SCHEDULED'")
        .param("u", userId)
        .query((rs, n) -> new DeletionState(true, rs.getObject("scheduled_for", OffsetDateTime.class)))
        .optional()
        .orElse(new DeletionState(false, null));
  }

  /** Emails a fresh confirmation code to accounts that have no password. */
  public void sendDeleteCode(UUID userId, String email) {
    if (authVerify(userId, null)[0]) {
      throw new CodedException(
          ErrorCode.VALIDATION_FAILED, "This account has a password; confirm with it instead");
    }
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    jdbc.sql(
            "INSERT INTO users.delete_codes (user_id, code_hash, attempts, expires_at) VALUES"
                + " (:u, :h, 0, now() + interval '10 minutes') ON CONFLICT (user_id) DO UPDATE SET"
                + " code_hash = :h, attempts = 0, expires_at = now() + interval '10 minutes'")
        .param("u", userId)
        .param("h", sha256(code))
        .update();
    notifier.email(
        email,
        "OTP",
        Map.of("name", "there", "code", code, "purpose", "confirm account deletion", "minutes", "10"));
  }

  @Transactional
  public DeletionState scheduleDeletion(UUID userId, String email, String password, String otp) {
    DeletionState existing = deletionState(userId);
    if (existing.scheduled()) {
      return existing;
    }
    boolean[] auth = authVerify(userId, password);
    if (auth[0]) {
      if (password == null || password.isBlank() || !auth[1]) {
        throw new CodedException(ErrorCode.VALIDATION_FAILED, "Password is incorrect");
      }
    } else {
      verifyCode(userId, otp);
    }
    assertNoBlockers(userId);
    OffsetDateTime when = OffsetDateTime.now(ZoneOffset.UTC).plusDays(graceDays);
    jdbc.sql(
            "INSERT INTO users.deletion_requests (user_id, email, status, requested_at,"
                + " scheduled_for, completed_at) VALUES (:u, :e, 'SCHEDULED', now(), :w, NULL) ON"
                + " CONFLICT (user_id) DO UPDATE SET status = 'SCHEDULED', email = :e,"
                + " requested_at = now(), scheduled_for = :w, completed_at = NULL")
        .param("u", userId)
        .param("e", email)
        .param("w", when)
        .update();
    notifier.email(
        email,
        "PRIVACY_DELETE_SCHEDULED",
        Map.of(
            "name", "there",
            "scheduledFor", HUMAN.format(when),
            "link", storefront + "/account/privacy"));
    return new DeletionState(true, when);
  }

  @Transactional
  public DeletionState cancelDeletion(UUID userId) {
    jdbc.sql(
            "UPDATE users.deletion_requests SET status = 'CANCELLED' WHERE user_id = :u AND status"
                + " = 'SCHEDULED'")
        .param("u", userId)
        .update();
    return new DeletionState(false, null);
  }

  private void verifyCode(UUID userId, String otp) {
    if (otp == null || otp.isBlank()) {
      throw new CodedException(
          ErrorCode.VALIDATION_FAILED, "A confirmation code is required; request one first");
    }
    Optional<String[]> row =
        jdbc.sql(
                "SELECT code_hash, attempts FROM users.delete_codes WHERE user_id = :u AND"
                    + " expires_at > now()")
            .param("u", userId)
            .query((rs, n) -> new String[] {rs.getString("code_hash"), rs.getString("attempts")})
            .optional();
    if (row.isEmpty() || Integer.parseInt(row.get()[1]) >= 5) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Confirmation code expired; request a new one");
    }
    if (!MessageDigest.isEqual(
        row.get()[0].getBytes(StandardCharsets.UTF_8), sha256(otp.trim()).getBytes(StandardCharsets.UTF_8))) {
      jdbc.sql("UPDATE users.delete_codes SET attempts = attempts + 1 WHERE user_id = :u")
          .param("u", userId)
          .update();
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Confirmation code is incorrect");
    }
    jdbc.sql("DELETE FROM users.delete_codes WHERE user_id = :u").param("u", userId).update();
  }

  /** @return {hasPassword, valid} */
  private boolean[] authVerify(UUID userId, String password) {
    try {
      String body =
          http.postJson(
              "http://auth-service/internal/privacy/users/{id}/verify-password",
              token,
              "{\"password\":" + jsonString(password) + "}",
              userId);
      return new boolean[] {
        body != null && body.contains("\"hasPassword\":true"),
        body != null && body.contains("\"valid\":true")
      };
    } catch (RuntimeException e) {
      log.warn("auth verify failed: {}", e.toString());
      throw new CodedException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Could not verify your identity right now");
    }
  }

  private void assertNoBlockers(UUID userId) {
    int openOrders = 0;
    int openReturns = 0;
    int pendingPayments = 0;
    try {
      String o =
          http.get(
              "http://order-service/internal/privacy/users/{id}/deletion-blockers", token, userId);
      openOrders = intField(o, "openOrders");
      openReturns = intField(o, "openReturns");
      String p =
          http.get(
              "http://payment-service/internal/privacy/users/{id}/deletion-blockers", token, userId);
      pendingPayments = intField(p, "pendingPayments");
    } catch (RuntimeException e) {
      log.warn("blocker check failed: {}", e.toString());
      throw new CodedException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "Could not check open orders right now; try again shortly");
    }
    if (openOrders + openReturns + pendingPayments > 0) {
      throw new CodedException(
          ErrorCode.ACCOUNT_DELETION_BLOCKED,
          "Account cannot be deleted yet: "
              + openOrders
              + " open order(s), "
              + openReturns
              + " open return/refund(s), "
              + pendingPayments
              + " pending payment(s). Wait until they are delivered, cancelled or settled.");
    }
  }

  private static int intField(String json, String field) {
    if (json == null) {
      throw new IllegalStateException("empty response");
    }
    Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
    return m.find() ? Integer.parseInt(m.group(1)) : 0;
  }

  /** Runs every due deletion; returns the number completed. Also the dev trigger's body. */
  public int runDue(Instant now) {
    List<UUID> due =
        jdbc.sql(
                "SELECT user_id FROM users.deletion_requests WHERE status = 'SCHEDULED' AND"
                    + " scheduled_for <= :now")
            .param("now", java.sql.Timestamp.from(now))
            .query(UUID.class)
            .list();
    int done = 0;
    for (UUID userId : due) {
      try {
        if (complete(userId)) {
          done++;
        }
      } catch (RuntimeException e) {
        log.error("Deletion of {} failed, will retry: {}", userId, e.toString());
      }
    }
    return done;
  }

  @Scheduled(fixedDelay = 900_000L, initialDelay = 180_000L)
  public void runDueScheduled() {
    int n = runDue(Instant.now());
    if (n > 0) {
      log.info("Completed {} account deletion(s)", n);
    }
  }

  private boolean complete(UUID userId) {
    try {
      assertNoBlockers(userId);
    } catch (CodedException e) {
      log.info("Deletion of {} postponed: {}", userId, e.getMessage());
      return false;
    }
    jdbc.sql("UPDATE users.user_profiles SET email = NULL, display_name = 'Deleted user', phone = NULL WHERE user_id = :u")
        .param("u", userId)
        .update();
    for (String sql :
        List.of(
            "DELETE FROM users.addresses WHERE user_id = :u",
            "DELETE FROM users.recently_viewed WHERE user_id = :u",
            "DELETE FROM users.user_preferences WHERE user_id = :u",
            "DELETE FROM users.referral_codes WHERE user_id = :u",
            "DELETE FROM users.delete_codes WHERE user_id = :u",
            "DELETE FROM users.export_jobs WHERE user_id = :u",
            "UPDATE users.memberships SET email = NULL, auto_renew = false WHERE user_id = :u",
            "UPDATE users.membership_payments SET email = NULL WHERE user_id = :u")) {
      jdbc.sql(sql).param("u", userId).update();
    }
    String event =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"type\":\"USER_DELETED\",\"userId\":\""
            + userId
            + "\",\"occurredAt\":\""
            + Instant.now()
            + "\"}";
    try {
      kafka.send(TOPIC, userId.toString(), event).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted publishing USER_DELETED", e);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException("could not publish USER_DELETED: " + e, e);
    }
    jdbc.sql(
            "UPDATE users.deletion_requests SET status = 'COMPLETED', completed_at = now(), email"
                + " = NULL WHERE user_id = :u")
        .param("u", userId)
        .update();
    return true;
  }

  // ----------------------------------------------------------------- helpers

  private static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String jsonString(String s) {
    if (s == null) {
      return "\"\"";
    }
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}
