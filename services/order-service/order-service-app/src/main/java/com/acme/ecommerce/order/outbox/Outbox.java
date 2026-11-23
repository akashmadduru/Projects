package com.acme.ecommerce.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes outbox rows (TD-P6-01). Uses the caller's transaction when there is one (JdbcClient joins
 * the Spring-managed transaction), so an order change and its side effects commit or roll back
 * together; without a transaction the row is committed on its own, which is still durable. Delivery
 * is {@link OutboxRelay}'s job. A second request for the same {@code idemKey} is ignored.
 */
@Component
public class Outbox {

  /** Jackson 2 with java.time support; payloads are read back with the same mapper. */
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /** What the relay knows how to deliver. */
  public enum Kind {
    ORDER_EVENT,
    RESTORE_STOCK,
    RELEASE_STOCK,
    RELEASE_DEALS,
    RELEASE_EXCHANGE,
    RELEASE_COUPON,
    RELEASE_COINS,
    CANCEL_PAYMENT,
    COD_COLLECTED,
    CLEAR_CART,
    ENSURE_COD_PAYMENT
  }

  private final JdbcClient jdbc;

  public Outbox(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Records an effect with no payload beyond the aggregate id; one per kind and aggregate. */
  public void enqueue(Kind kind, UUID aggregateId) {
    enqueue(kind, aggregateId, null, kind + ":" + aggregateId);
  }

  /** Records an effect with a payload, deduplicated by {@code idemKey}. */
  public void enqueue(Kind kind, UUID aggregateId, Object payload, String idemKey) {
    jdbc.sql(
            "INSERT INTO orders.outbox (aggregate_id, kind, payload, idem_key)"
                + " VALUES (:a, :k, CAST(:p AS jsonb), :i) ON CONFLICT (idem_key) DO NOTHING")
        .param("a", aggregateId)
        .param("k", kind.name())
        .param("p", toJson(payload))
        .param("i", idemKey)
        .update();
  }

  /**
   * Like {@link #enqueue(Kind, UUID, Object, String)} but the relay will not take the row before
   * {@code notBefore} has passed (it must not race an inline delivery still in flight).
   */
  public void enqueue(
      Kind kind, UUID aggregateId, Object payload, String idemKey, Duration notBefore) {
    jdbc.sql(
            "INSERT INTO orders.outbox (aggregate_id, kind, payload, idem_key, next_attempt_at)"
                + " VALUES (:a, :k, CAST(:p AS jsonb), :i, now() + make_interval(secs => :d))"
                + " ON CONFLICT (idem_key) DO NOTHING")
        .param("a", aggregateId)
        .param("k", kind.name())
        .param("p", toJson(payload))
        .param("i", idemKey)
        .param("d", notBefore.toSeconds())
        .update();
  }

  /** Marks a row DONE if it is still PENDING (an inline delivery succeeded). Returns rows set. */
  public int complete(String idemKey) {
    return jdbc.sql(
            "UPDATE orders.outbox SET status = 'DONE', done_at = now(), attempts = attempts + 1,"
                + " last_error = NULL WHERE idem_key = :i AND status = 'PENDING'")
        .param("i", idemKey)
        .update();
  }

  static String toJson(Object payload) {
    if (payload == null) {
      return "{}";
    }
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Outbox payload is not serialisable", e);
    }
  }
}
