package com.acme.ecommerce.order.repository;

import com.acme.ecommerce.order.domain.OrderReturn;
import com.acme.ecommerce.order.domain.ReturnStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, UUID> {

  Page<OrderReturn> findByStatus(ReturnStatus status, Pageable pageable);

  long countByUserIdAndCreatedAtAfter(UUID userId, java.time.Instant after);

  @Query("select count(distinct r.order.id) from OrderReturn r where r.userId = :userId")
  long countDistinctOrders(@org.springframework.data.repository.query.Param("userId") UUID userId);

  /** Takes the 2-minute refund lease; 0 rows when the return is not PICKED_UP or leased. */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE orders.order_returns SET refund_lease_until = :until"
              + " WHERE id = :id AND status = 'PICKED_UP'"
              + " AND (refund_lease_until IS NULL OR refund_lease_until < now())",
      nativeQuery = true)
  int claimRefundLease(
      @org.springframework.data.repository.query.Param("id") UUID id,
      @org.springframework.data.repository.query.Param("until") java.time.Instant until);

  /** Frees the lease only if it is still the one this caller took (it may have expired). */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE orders.order_returns SET refund_lease_until = NULL"
              + " WHERE id = :id AND refund_lease_until = :mine",
      nativeQuery = true)
  int releaseRefundLease(
      @org.springframework.data.repository.query.Param("id") UUID id,
      @org.springframework.data.repository.query.Param("mine") java.time.Instant mine);

  /** Starts the next card-leg key; 0 rows when another caller already did (the loser gets 409). */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE orders.order_returns SET rest_leg_attempt = rest_leg_attempt + 1"
              + " WHERE id = :id AND rest_leg_attempt = :n",
      nativeQuery = true)
  int bumpRestLegAttempt(
      @org.springframework.data.repository.query.Param("id") UUID id,
      @org.springframework.data.repository.query.Param("n") int attempt);

  /** Returns whose card leg is waiting for payment-service to settle it (oldest first). */
  @Query(
      value =
          "SELECT id FROM orders.order_returns WHERE status = 'PICKED_UP'"
              + " AND rest_leg_state = 'PENDING' ORDER BY updated_at LIMIT 50",
      nativeQuery = true)
  List<UUID> findPendingRestLegIds();

  /** Rows of [ReturnStatus, Long] for the admin dashboard. */
  @Query("select r.status, count(r) from OrderReturn r group by r.status")
  List<Object[]> countGroupedByStatus();
}
