package com.acme.ecommerce.order.repository;

import com.acme.ecommerce.order.domain.OrderShipment;
import com.acme.ecommerce.order.domain.OrderStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderShipmentRepository extends JpaRepository<OrderShipment, UUID> {

  @Query("select s.order.id from OrderShipment s where s.id = :id")
  Optional<UUID> findOrderId(@Param("id") UUID id);

  Page<OrderShipment> findBySellerIdAndStatus(UUID sellerId, OrderStatus status, Pageable pageable);

  Page<OrderShipment> findBySellerIdAndStatusNot(
      UUID sellerId, OrderStatus status, Pageable pageable);
}
