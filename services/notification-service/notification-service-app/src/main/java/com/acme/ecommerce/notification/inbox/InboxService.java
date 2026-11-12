package com.acme.ecommerce.notification.inbox;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class InboxService {

  public record Item(
      UUID id,
      String type,
      String title,
      String body,
      String link,
      boolean read,
      OffsetDateTime readAt,
      OffsetDateTime createdAt) {}

  public record Page(List<Item> content, int page, long unreadCount) {}

  private final JdbcClient jdbc;

  public InboxService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** @return true when a row was inserted (false = duplicate dedupeKey). */
  public boolean add(
      UUID userId, String type, String title, String body, String link, String dedupeKey) {
    return jdbc.sql(
                "INSERT INTO notification.notifications (user_id, type, title, body, link,"
                    + " dedupe_key) VALUES (:u, :t, :title, :body, :link, :dk) ON CONFLICT"
                    + " (dedupe_key) DO NOTHING")
            .param("u", userId)
            .param("t", type)
            .param("title", clip(title, 200))
            .param("body", clip(body, 1000))
            .param("link", clip(link, 500))
            .param("dk", dedupeKey)
            .update()
        > 0;
  }

  public Page list(UUID userId, int page, int size, boolean unreadOnly) {
    int sz = Math.min(Math.max(size, 1), 100);
    int pg = Math.max(page, 0);
    List<Item> items =
        jdbc.sql(
                "SELECT id, type, title, body, link, read_at, created_at FROM"
                    + " notification.notifications WHERE user_id = :u"
                    + (unreadOnly ? " AND read_at IS NULL" : "")
                    + " ORDER BY created_at DESC, id LIMIT :lim OFFSET :off")
            .param("u", userId)
            .param("lim", sz)
            .param("off", pg * sz)
            .query(
                (rs, n) ->
                    new Item(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("link"),
                        rs.getObject("read_at", OffsetDateTime.class) != null,
                        rs.getObject("read_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
            .list();
    return new Page(items, pg, unread(userId));
  }

  public long unread(UUID userId) {
    return jdbc.sql(
            "SELECT count(*) FROM notification.notifications WHERE user_id = :u AND read_at IS"
                + " NULL")
        .param("u", userId)
        .query(Long.class)
        .single();
  }

  public long markRead(UUID userId, UUID id) {
    int n =
        jdbc.sql(
                "UPDATE notification.notifications SET read_at = COALESCE(read_at, now()) WHERE id"
                    + " = :id AND user_id = :u")
            .param("id", id)
            .param("u", userId)
            .update();
    if (n == 0) {
      throw new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Notification not found");
    }
    return unread(userId);
  }

  public long readAll(UUID userId) {
    jdbc.sql(
            "UPDATE notification.notifications SET read_at = now() WHERE user_id = :u AND read_at"
                + " IS NULL")
        .param("u", userId)
        .update();
    return 0;
  }

  private static String clip(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
