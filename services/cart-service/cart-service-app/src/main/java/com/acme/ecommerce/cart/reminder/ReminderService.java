package com.acme.ecommerce.cart.reminder;

import com.acme.ecommerce.cart.client.NotificationFeignClient;
import com.acme.ecommerce.cart.client.NotificationFeignClient.EmailBody;
import com.acme.ecommerce.cart.client.NotificationFeignClient.InboxBody;
import com.acme.ecommerce.cart.client.UserFeignClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Abandoned-cart reminders: hourly job + dev trigger. One reminder per cart update. */
@Service
public class ReminderService {

  private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
  static final int DEFAULT_MIN_AGE_MINUTES = 240;

  private record CartRow(UUID cartId, UUID userId, String email) {}

  private final JdbcClient jdbc;
  private final NotificationFeignClient notifications;
  private final UserFeignClient users;
  private final String storefront;

  public ReminderService(
      JdbcClient jdbc,
      NotificationFeignClient notifications,
      UserFeignClient users,
      @Value("${cart.storefront-url:http://localhost:3000}") String storefront) {
    this.jdbc = jdbc;
    this.notifications = notifications;
    this.users = users;
    this.storefront = storefront;
  }

  @Scheduled(cron = "${cart.reminders.cron:0 7 * * * *}")
  public void scheduled() {
    run(DEFAULT_MIN_AGE_MINUTES);
  }

  /** @return number of reminders sent */
  public int run(int minAgeMinutes) {
    List<CartRow> carts =
        jdbc.sql(
                "SELECT c.id, c.user_id, c.email FROM cart.carts c WHERE c.email IS NOT NULL AND"
                    + " c.updated_at <= now() - make_interval(mins => :min) AND c.updated_at >="
                    + " now() - interval '7 days' AND (c.reminder_sent_at IS NULL OR"
                    + " c.reminder_sent_at < c.updated_at) AND EXISTS (SELECT 1 FROM"
                    + " cart.cart_items i WHERE i.cart_id = c.id) LIMIT 500")
            .param("min", minAgeMinutes)
            .query(
                (rs, n) ->
                    new CartRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("email")))
            .list();
    int sent = 0;
    for (CartRow c : carts) {
      try {
        if (remind(c)) {
          sent++;
        }
      } catch (RuntimeException e) {
        log.warn("Reminder for cart {} failed: {}", c.cartId(), e.toString());
      }
    }
    if (sent > 0) {
      log.info("Sent {} cart reminders", sent);
    }
    return sent;
  }

  private boolean remind(CartRow c) {
    boolean allowed = true;
    try {
      allowed = users.preferences(c.userId()).emailPromotions();
    } catch (RuntimeException e) {
      log.debug("Preferences lookup failed for {}, assuming allowed", c.userId());
    }
    if (!allowed) {
      markSent(c.cartId());
      return false;
    }
    List<String> lines =
        jdbc.sql(
                "SELECT name, quantity FROM cart.cart_items WHERE cart_id = :c ORDER BY"
                    + " (unit_price * quantity) DESC LIMIT 3")
            .param("c", c.cartId())
            .query((rs, n) -> "- " + rs.getString("name") + " x" + rs.getInt("quantity"))
            .list();
    int total =
        jdbc.sql("SELECT count(*) FROM cart.cart_items WHERE cart_id = :c")
            .param("c", c.cartId())
            .query(Integer.class)
            .single();
    Map<String, String> data = new HashMap<>();
    data.put("name", "there");
    data.put(
        "items",
        String.join("\n", lines) + (total > lines.size() ? "\n...and " + (total - lines.size()) + " more" : ""));
    data.put("link", storefront + "/cart");
    notifications.sendEmail(new EmailBody(c.email(), "CART_REMINDER", data));
    try {
      notifications.inbox(
          new InboxBody(
              c.userId().toString(),
              "PROMO",
              "You left items in your cart",
              total + " item(s) are waiting for you.",
              "/cart"));
    } catch (RuntimeException e) {
      log.debug("Reminder inbox entry failed: {}", e.toString());
    }
    markSent(c.cartId());
    return true;
  }

  private void markSent(UUID cartId) {
    jdbc.sql("UPDATE cart.carts SET reminder_sent_at = now() WHERE id = :id")
        .param("id", cartId)
        .update();
  }
}
