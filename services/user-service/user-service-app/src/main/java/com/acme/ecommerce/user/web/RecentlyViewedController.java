package com.acme.ecommerce.user.web;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's recently viewed products (newest first, capped at 50). */
@RestController
@RequestMapping("/api/users/me/recently-viewed")
public class RecentlyViewedController {

  static final int MAX = 50;

  public record ViewRequest(@NotNull UUID productId) {}

  public record Viewed(UUID productId, OffsetDateTime viewedAt) {}

  private final JdbcClient jdbc;

  public RecentlyViewedController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping
  public List<Viewed> list(@AuthenticationPrincipal Jwt jwt) {
    return jdbc.sql(
            "SELECT product_id, viewed_at FROM users.recently_viewed WHERE user_id = :u"
                + " ORDER BY viewed_at DESC LIMIT " + MAX)
        .param("u", UUID.fromString(jwt.getSubject()))
        .query(
            (rs, n) ->
                new Viewed(
                    rs.getObject("product_id", UUID.class),
                    rs.getObject("viewed_at", OffsetDateTime.class)))
        .list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  public void record(@AuthenticationPrincipal Jwt jwt, @RequestBody ViewRequest request) {
    UUID user = UUID.fromString(jwt.getSubject());
    jdbc.sql(
            "INSERT INTO users.recently_viewed (user_id, product_id) VALUES (:u, :p)"
                + " ON CONFLICT (user_id, product_id) DO UPDATE SET viewed_at = now()")
        .param("u", user)
        .param("p", request.productId())
        .update();
    jdbc.sql(
            "DELETE FROM users.recently_viewed WHERE user_id = :u AND product_id NOT IN"
                + " (SELECT product_id FROM users.recently_viewed WHERE user_id = :u"
                + " ORDER BY viewed_at DESC LIMIT " + MAX + ")")
        .param("u", user)
        .update();
  }
}
