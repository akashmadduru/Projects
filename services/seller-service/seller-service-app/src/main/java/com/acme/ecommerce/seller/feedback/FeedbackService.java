package com.acme.ecommerce.seller.feedback;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.seller.Paged;
import com.acme.ecommerce.seller.client.OrderFeignClient;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Seller ratings: one per (delivered shipment, user); aggregates kept on sellers. */
@Service
public class FeedbackService {

  public record FeedbackRequest(UUID shipmentId, int rating, String comment) {}

  public record Feedback(UUID id, UUID shipmentId, int rating, String comment, OffsetDateTime createdAt) {}

  private final JdbcClient jdbc;
  private final OrderFeignClient orders;

  public FeedbackService(JdbcClient jdbc, OrderFeignClient orders) {
    this.jdbc = jdbc;
    this.orders = orders;
  }

  @Transactional
  public Feedback create(UUID userId, UUID sellerId, FeedbackRequest r) {
    if (r.shipmentId() == null) {
      throw ValidationException.forField("shipmentId", "Required");
    }
    if (r.rating() < 1 || r.rating() > 5) {
      throw ValidationException.forField("rating", "Must be 1-5");
    }
    if (r.comment() != null && r.comment().length() > 500) {
      throw ValidationException.forField("comment", "At most 500 characters");
    }
    if (jdbc.sql("SELECT count(*) FROM sellers WHERE id = :i").param("i", sellerId).query(Long.class).single() == 0) {
      throw new ResourceNotFoundException("Seller", sellerId);
    }
    OrderFeignClient.ShipmentInfo ship;
    try {
      ship = orders.shipment(r.shipmentId());
    } catch (feign.FeignException.NotFound e) {
      throw new ResourceNotFoundException("Shipment", r.shipmentId());
    } catch (RuntimeException e) {
      throw new CodedException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Order lookup unavailable", e);
    }
    if (ship == null || !userId.equals(ship.userId()) || !sellerId.equals(ship.sellerId())) {
      throw new ResourceNotFoundException("Shipment", r.shipmentId());
    }
    if (!"DELIVERED".equals(ship.status())) {
      throw new CodedException(ErrorCode.REVIEW_NOT_ELIGIBLE, "Only delivered shipments can be rated");
    }
    UUID id = UUID.randomUUID();
    try {
      jdbc.sql(
              "INSERT INTO seller_feedback (id, seller_id, order_id, shipment_id, user_id, rating, comment)"
                  + " VALUES (:id, :s, :o, :sh, :u, :r, :c)")
          .param("id", id).param("s", sellerId).param("o", ship.orderId()).param("sh", ship.shipmentId())
          .param("u", userId).param("r", r.rating()).param("c", r.comment())
          .update();
    } catch (DuplicateKeyException e) {
      throw new CodedException(ErrorCode.REVIEW_ALREADY_EXISTS, "You already rated this shipment");
    }
    jdbc.sql(
            "UPDATE sellers SET rating_avg = COALESCE((SELECT round(avg(rating)::numeric, 2) FROM"
                + " seller_feedback WHERE seller_id = :s), 0), rating_count = (SELECT count(*) FROM"
                + " seller_feedback WHERE seller_id = :s) WHERE id = :s")
        .param("s", sellerId)
        .update();
    return new Feedback(id, ship.shipmentId(), r.rating(), r.comment(), OffsetDateTime.now());
  }

  @Transactional(readOnly = true)
  public Paged<Feedback> list(UUID sellerId, int page, int size) {
    int s = Paged.clampSize(size);
    long total = jdbc.sql("SELECT count(*) FROM seller_feedback WHERE seller_id = :s").param("s", sellerId).query(Long.class).single();
    var rows =
        jdbc.sql("SELECT * FROM seller_feedback WHERE seller_id = :s ORDER BY created_at DESC LIMIT :l OFFSET :o")
            .param("s", sellerId).param("l", s).param("o", (long) Math.max(page, 0) * s)
            .query((rs, n) -> new Feedback(rs.getObject("id", UUID.class), rs.getObject("shipment_id", UUID.class),
                rs.getInt("rating"), rs.getString("comment"), rs.getObject("created_at", OffsetDateTime.class)))
            .list();
    return Paged.of(rows, Math.max(page, 0), s, total);
  }
}
