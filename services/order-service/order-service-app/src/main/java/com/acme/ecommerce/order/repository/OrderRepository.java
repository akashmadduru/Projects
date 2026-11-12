package com.acme.ecommerce.order.repository;

import com.acme.ecommerce.order.api.dto.OrderSummaryResponse;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.OrderStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository
    extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

  Optional<Order> findByIdAndUserId(UUID id, UUID userId);

  Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

  long countByUserIdAndStatusNot(UUID userId, OrderStatus status);

  List<Order> findByStatusAndPlacedAtBefore(OrderStatus status, Instant cutoff);

  /** The user's most recent order, not in the excluded statuses, that contains the product. */
  @Query(
      "select o from Order o join o.items i where o.userId = :userId and i.productId = :productId"
          + " and o.status not in :excluded order by o.placedAt desc")
  List<Order> findContainingProduct(
      @Param("userId") UUID userId,
      @Param("productId") UUID productId,
      @Param("excluded") Collection<OrderStatus> excluded,
      Pageable pageable);

  @Query(
      value =
          "select new com.acme.ecommerce.order.api.dto.OrderSummaryResponse("
              + "o.id, cast(o.status as string), o.currency, o.totalAmount, o.placedAt, "
              + "(select coalesce(sum(i.quantity), 0L) from OrderItem i where i.order = o), "
              + "cast(o.paymentMethod as string), cast(o.paymentStatus as string)) "
              + "from Order o where o.userId = :userId",
      countQuery = "select count(o) from Order o where o.userId = :userId")
  Page<OrderSummaryResponse> findSummaries(@Param("userId") UUID userId, Pageable pageable);

  /** Rows of [product_id (UUID), orders together (Long)] for orders that also contain the product. */
  @Query(
      value =
          "select i2.product_id, count(distinct o.id) as score from orders.order_items i1"
              + " join orders.order_items i2 on i2.order_id = i1.order_id and i2.product_id <> i1.product_id"
              + " join orders.orders o on o.id = i1.order_id"
              + " where i1.product_id = :productId and o.status <> 'CANCELLED'"
              + " group by i2.product_id order by score desc, i2.product_id limit :lim",
      nativeQuery = true)
  List<Object[]> boughtTogether(
      @Param("productId") UUID productId, @Param("lim") int limit);

  /** Rows of [OrderStatus, Long] for the admin dashboard. */
  @Query("select o.status, count(o) from Order o group by o.status")
  List<Object[]> countGroupedByStatus();

  /**
   * Rows of [currency, gross (BigDecimal), refunded (BigDecimal), order count (Long)] since the
   * cutoff. Plain sums only - net = gross - refunded is computed in Java so the JPQL needs no
   * mixed-type arithmetic (validated at startup; a type error would stop the service booting).
   */
  @Query(
      "select o.currency, sum(o.totalAmount), sum(o.refundedAmount), count(o)"
          + " from Order o where o.placedAt >= :since and o.status not in :excluded"
          + " group by o.currency")
  List<Object[]> revenueSince(
      @Param("since") Instant since, @Param("excluded") Collection<OrderStatus> excluded);

  /** Rows of [product_id, variant_id, last ordered (Timestamp), times ordered] from DELIVERED orders. */
  @Query(
      value =
          "select i.product_id, i.variant_id, max(o.placed_at) as last_at, count(distinct o.id) as times"
              + " from orders.order_items i join orders.orders o on o.id = i.order_id"
              + " where o.user_id = :userId and o.status = 'DELIVERED'"
              + " group by i.product_id, i.variant_id order by last_at desc limit :lim",
      nativeQuery = true)
  List<Object[]> buyAgain(@Param("userId") UUID userId, @Param("lim") int limit);

  /** Rows of [user_id, last placed_at] over non-cancelled orders. */
  @Query(
      value =
          "select o.user_id, max(o.placed_at) from orders.orders o"
              + " where o.user_id in (:ids) and o.status <> 'CANCELLED' group by o.user_id",
      nativeQuery = true)
  List<Object[]> lastOrderDates(@Param("ids") Collection<UUID> ids);
}
