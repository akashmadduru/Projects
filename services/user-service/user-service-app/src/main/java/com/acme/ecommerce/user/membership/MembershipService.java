package com.acme.ecommerce.user.membership;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.user.client.PaymentFeignClient;
import com.acme.ecommerce.user.client.PaymentFeignClient.CreatePayment;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {

  private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

  public static final List<String> BENEFITS =
      List.of("Free shipping on every order", "2x coins on every order", "Early access to deals");

  public record PlanView(String code, String name, BigDecimal price, String currency, int days) {}

  public record MembershipView(
      boolean active,
      String plan,
      String status,
      OffsetDateTime endsAt,
      boolean autoRenew,
      List<String> benefits,
      List<PlanView> plans) {}

  public record JoinResult(MembershipView membership, Map<String, Object> payment) {}

  public record Lookup(boolean active, String plan, OffsetDateTime endsAt) {}

  private record MemberRow(
      String plan, String status, OffsetDateTime endsAt, boolean autoRenew, String email) {}

  private final JdbcClient jdbc;
  private final PaymentFeignClient payments;
  private final Notifier notifier;
  private final String storefront;

  public MembershipService(
      JdbcClient jdbc,
      PaymentFeignClient payments,
      Notifier notifier,
      @Value("${user.storefront-url:http://localhost:3000}") String storefront) {
    this.jdbc = jdbc;
    this.payments = payments;
    this.notifier = notifier;
    this.storefront = storefront;
  }

  @Transactional(readOnly = true)
  public MembershipView view(UUID userId, String currency) {
    Optional<MemberRow> row = find(userId);
    boolean active =
        row.map(r -> isActive(r.status(), r.endsAt())).orElse(false);
    return new MembershipView(
        active,
        row.map(MemberRow::plan).orElse(null),
        row.map(MemberRow::status).orElse(null),
        row.map(MemberRow::endsAt).orElse(null),
        row.map(MemberRow::autoRenew).orElse(false),
        BENEFITS,
        plans(currency));
  }

  @Transactional(readOnly = true)
  public Lookup lookup(UUID userId) {
    Optional<MemberRow> row = find(userId);
    boolean active = row.map(r -> isActive(r.status(), r.endsAt())).orElse(false);
    return new Lookup(
        active,
        active ? row.get().plan() : null,
        active ? row.get().endsAt() : null);
  }

  /**
   * Starts a purchase: records an attempt and asks payment-service for a MEMBERSHIP payment. Not
   * transactional around the remote call on purpose; the attempt row is committed first.
   */
  public JoinResult join(UUID userId, String email, String plan, String currency) {
    String cur = currency == null ? "INR" : currency.toUpperCase(Locale.ROOT);
    BigDecimal price =
        jdbc.sql(
                "SELECT p.price FROM users.membership_plan_prices p JOIN users.membership_plans m"
                    + " ON m.code = p.plan_code WHERE p.plan_code = :plan AND p.currency = :cur"
                    + " AND m.active")
            .param("plan", plan)
            .param("cur", cur)
            .query(BigDecimal.class)
            .optional()
            .orElseThrow(
                () ->
                    new CodedException(
                        ErrorCode.VALIDATION_FAILED, "Unknown plan or currency: " + plan + "/" + cur));
    UUID ref = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO users.membership_payments (id, user_id, plan, currency, amount, email)"
                + " VALUES (:id, :u, :plan, :cur, :amt, :email)")
        .param("id", ref)
        .param("u", userId)
        .param("plan", plan)
        .param("cur", cur)
        .param("amt", price)
        .param("email", email)
        .update();
    Map<String, Object> payment;
    try {
      payment =
          payments.create(new CreatePayment(ref, ref, "MEMBERSHIP", userId, price, cur, "CARD"));
    } catch (RuntimeException e) {
      log.warn("Membership payment creation failed: {}", e.toString());
      jdbc.sql("UPDATE users.membership_payments SET status = 'FAILED' WHERE id = :id")
          .param("id", ref)
          .update();
      throw new CodedException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "Payment service is unavailable", e);
    }
    Object pid = payment == null ? null : payment.get("paymentId");
    if (pid != null) {
      jdbc.sql("UPDATE users.membership_payments SET payment_id = :p WHERE id = :id")
          .param("p", pid.toString())
          .param("id", ref)
          .update();
    }
    return new JoinResult(view(userId, cur), payment);
  }

  /** Idempotent: a SUCCEEDED/FAILED attempt is never processed twice. */
  @Transactional
  public void paymentResult(UUID referenceId, String status, String paymentId) {
    Optional<PaymentAttempt> attempt =
        jdbc.sql(
                "SELECT user_id, plan, email, status FROM users.membership_payments WHERE id = :id"
                    + " FOR UPDATE")
            .param("id", referenceId)
            .query(
                (rs, n) ->
                    new PaymentAttempt(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("plan"),
                        rs.getString("email"),
                        rs.getString("status")))
            .optional();
    if (attempt.isEmpty()) {
      throw new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Unknown membership payment");
    }
    PaymentAttempt a = attempt.get();
    if (!"PENDING".equals(a.status())) {
      return;
    }
    String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
    boolean ok = s.equals("SUCCEEDED") || s.equals("PAID") || s.equals("SUCCESS");
    boolean failed = s.equals("FAILED") || s.equals("CANCELLED") || s.equals("CANCELED");
    if (!ok && !failed) {
      return;
    }
    jdbc.sql(
            "UPDATE users.membership_payments SET status = :st, payment_id = COALESCE(:pid,"
                + " payment_id) WHERE id = :id")
        .param("st", ok ? "SUCCEEDED" : "FAILED")
        .param("pid", paymentId)
        .param("id", referenceId)
        .update();
    if (!ok) {
      return;
    }
    int days =
        jdbc.sql("SELECT days FROM users.membership_plans WHERE code = :c")
            .param("c", a.plan())
            .query(Integer.class)
            .single();
    // Renewal while still active extends from the current end date.
    jdbc.sql(
            "INSERT INTO users.memberships (user_id, plan, status, starts_at, ends_at, payment_id,"
                + " email) VALUES (:u, :plan, 'ACTIVE', now(), now() + make_interval(days => :d),"
                + " :pid, :email) ON CONFLICT (user_id) DO UPDATE SET plan = EXCLUDED.plan,"
                + " status = 'ACTIVE', auto_renew = false,"
                + " starts_at = CASE WHEN users.memberships.status IN ('ACTIVE','CANCELLED') AND"
                + " users.memberships.ends_at > now() THEN users.memberships.starts_at ELSE now() END,"
                + " ends_at = CASE WHEN users.memberships.status IN ('ACTIVE','CANCELLED') AND"
                + " users.memberships.ends_at > now() THEN users.memberships.ends_at +"
                + " make_interval(days => :d) ELSE now() + make_interval(days => :d) END,"
                + " payment_id = EXCLUDED.payment_id, email = COALESCE(EXCLUDED.email,"
                + " users.memberships.email), updated_at = now()")
        .param("u", a.userId())
        .param("plan", a.plan())
        .param("d", days)
        .param("pid", paymentId)
        .param("email", a.email())
        .update();
    OffsetDateTime ends =
        jdbc.sql("SELECT ends_at FROM users.memberships WHERE user_id = :u")
            .param("u", a.userId())
            .query(OffsetDateTime.class)
            .single();
    notifier.email(
        a.email(),
        "MEMBERSHIP_WELCOME",
        Map.of(
            "name", "there",
            "plan", a.plan(),
            "endsAt", ends.toLocalDate().toString(),
            "link", storefront + "/account/membership"));
    notifier.inbox(
        a.userId(),
        "ACCOUNT",
        "Welcome to Plus",
        "Free shipping, 2x coins and early deal access are active until "
            + ends.toLocalDate()
            + ".",
        "/account/membership");
  }

  @Transactional
  public MembershipView cancel(UUID userId) {
    int n =
        jdbc.sql(
                "UPDATE users.memberships SET status = 'CANCELLED', auto_renew = false, updated_at"
                    + " = now() WHERE user_id = :u AND status = 'ACTIVE' AND ends_at > now()")
            .param("u", userId)
            .update();
    if (n == 0 && !lookup(userId).active()) {
      throw new CodedException(ErrorCode.DOMAIN_RULE_VIOLATION, "No active membership to cancel");
    }
    return view(userId, "INR");
  }

  /** Daily: flips lapsed memberships to EXPIRED and tells the member. */
  @Scheduled(cron = "${user.membership.expiry-cron:0 15 3 * * *}")
  public void expireLapsed() {
    List<ExpiredRow> rows =
        jdbc.sql(
                "UPDATE users.memberships SET status = 'EXPIRED', updated_at = now() WHERE status IN"
                    + " ('ACTIVE','CANCELLED') AND ends_at <= now() RETURNING user_id, email")
            .query(
                (rs, n) ->
                    new ExpiredRow(rs.getObject("user_id", UUID.class), rs.getString("email")))
            .list();
    for (ExpiredRow r : rows) {
      notifier.email(
          r.email(),
          "MEMBERSHIP_EXPIRED",
          Map.of("name", "there", "link", storefront + "/account/membership"));
      notifier.inbox(
          r.userId(),
          "ACCOUNT",
          "Your Plus membership has ended",
          "Renew to keep free shipping, 2x coins and early deal access.",
          "/account/membership");
    }
    if (!rows.isEmpty()) {
      log.info("Expired {} memberships", rows.size());
    }
  }

  private record PaymentAttempt(UUID userId, String plan, String email, String status) {}

  private record ExpiredRow(UUID userId, String email) {}

  private static boolean isActive(String status, OffsetDateTime endsAt) {
    return ("ACTIVE".equals(status) || "CANCELLED".equals(status))
        && endsAt != null
        && endsAt.isAfter(OffsetDateTime.now());
  }

  private Optional<MemberRow> find(UUID userId) {
    return jdbc.sql(
            "SELECT plan, status, ends_at, auto_renew, email FROM users.memberships WHERE user_id"
                + " = :u")
        .param("u", userId)
        .query(
            (rs, n) ->
                new MemberRow(
                    rs.getString("plan"),
                    rs.getString("status"),
                    rs.getObject("ends_at", OffsetDateTime.class),
                    rs.getBoolean("auto_renew"),
                    rs.getString("email")))
        .optional();
  }

  private List<PlanView> plans(String currency) {
    String cur = currency == null ? "INR" : currency.toUpperCase(Locale.ROOT);
    return jdbc.sql(
            "SELECT m.code, m.name, m.days, p.price, p.currency FROM users.membership_plans m JOIN"
                + " users.membership_plan_prices p ON p.plan_code = m.code WHERE m.active AND"
                + " p.currency = :cur ORDER BY m.days")
        .param("cur", cur)
        .query(
            (rs, n) ->
                new PlanView(
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getBigDecimal("price"),
                    rs.getString("currency").trim(),
                    rs.getInt("days")))
        .list();
  }
}
