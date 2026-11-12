package com.acme.ecommerce.user.membership;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreferenceService {

  public record Preferences(
      boolean emailOrders,
      boolean emailPromotions,
      boolean pushOrders,
      boolean pushPromotions,
      boolean sms) {}

  /** Partial update: null fields keep their current value. */
  public record PreferencesUpdate(
      Boolean emailOrders,
      Boolean emailPromotions,
      Boolean pushOrders,
      Boolean pushPromotions,
      Boolean sms) {}

  private static final Preferences DEFAULTS = new Preferences(true, true, true, false, true);

  private final JdbcClient jdbc;

  public PreferenceService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Preferences get(UUID userId) {
    return jdbc.sql(
            "SELECT email_orders, email_promotions, push_orders, push_promotions, sms FROM"
                + " users.user_preferences WHERE user_id = :u")
        .param("u", userId)
        .query(
            (rs, n) ->
                new Preferences(
                    rs.getBoolean("email_orders"),
                    rs.getBoolean("email_promotions"),
                    rs.getBoolean("push_orders"),
                    rs.getBoolean("push_promotions"),
                    rs.getBoolean("sms")))
        .optional()
        .orElse(DEFAULTS);
  }

  @Transactional
  public Preferences update(UUID userId, PreferencesUpdate u) {
    Preferences cur = get(userId);
    Preferences next =
        new Preferences(
            u.emailOrders() != null ? u.emailOrders() : cur.emailOrders(),
            u.emailPromotions() != null ? u.emailPromotions() : cur.emailPromotions(),
            u.pushOrders() != null ? u.pushOrders() : cur.pushOrders(),
            u.pushPromotions() != null ? u.pushPromotions() : cur.pushPromotions(),
            u.sms() != null ? u.sms() : cur.sms());
    jdbc.sql(
            "INSERT INTO users.user_preferences (user_id, email_orders, email_promotions,"
                + " push_orders, push_promotions, sms) VALUES (:u, :eo, :ep, :po, :pp, :sms) ON"
                + " CONFLICT (user_id) DO UPDATE SET email_orders = :eo, email_promotions = :ep,"
                + " push_orders = :po, push_promotions = :pp, sms = :sms, updated_at = now()")
        .param("u", userId)
        .param("eo", next.emailOrders())
        .param("ep", next.emailPromotions())
        .param("po", next.pushOrders())
        .param("pp", next.pushPromotions())
        .param("sms", next.sms())
        .update();
    return next;
  }
}
