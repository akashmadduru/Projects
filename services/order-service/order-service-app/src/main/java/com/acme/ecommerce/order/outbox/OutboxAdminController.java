package com.acme.ecommerce.order.outbox;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin view of the outbox (TD-P6-01): rows the relay gave up on ({@code DEAD}) or that are still
 * waiting, and a retry that puts a dead row back in the queue. Retrying is safe because every
 * receiver is idempotent.
 */
@RestController
@RequestMapping("/api/admin/orders/outbox")
@PreAuthorize("hasRole('ADMIN')")
public class OutboxAdminController {

  public record OutboxRow(
      UUID id,
      UUID aggregateId,
      String kind,
      String status,
      int attempts,
      String lastError,
      Instant createdAt,
      Instant nextAttemptAt) {}

  private final JdbcClient jdbc;

  public OutboxAdminController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping
  public List<OutboxRow> list(@RequestParam(defaultValue = "DEAD") String status) {
    String s = "PENDING".equalsIgnoreCase(status) ? "PENDING" : "DEAD";
    return jdbc.sql(
            "SELECT id, aggregate_id, kind, status, attempts, last_error, created_at,"
                + " next_attempt_at FROM orders.outbox WHERE status = :s ORDER BY seq LIMIT 200")
        .param("s", s)
        .query(
            (rs, n) ->
                new OutboxRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getString("last_error"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("next_attempt_at").toInstant()))
        .list();
  }

  /** Puts a DEAD row back in the queue with a fresh attempt budget. */
  @PostMapping("/{id}/retry")
  public void retry(@PathVariable UUID id) {
    int n =
        jdbc.sql(
                "UPDATE orders.outbox SET status = 'PENDING', attempts = 0, next_attempt_at = now()"
                    + " WHERE id = :i AND status = 'DEAD'")
            .param("i", id)
            .update();
    if (n == 0) {
      throw new ResourceNotFoundException("Dead outbox row", id);
    }
  }
}
