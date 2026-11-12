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
}
