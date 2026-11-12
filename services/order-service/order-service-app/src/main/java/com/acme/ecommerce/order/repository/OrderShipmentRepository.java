package com.acme.ecommerce.order.repository;

import com.acme.ecommerce.order.domain.OrderShipment;
import com.acme.ecommerce.order.domain.OrderStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderShipmentRepository extends JpaRepository<OrderShipment, UUID> {

  Page<OrderShipment> findBySellerIdAndStatus(UUID sellerId, OrderStatus status, Pageable pageable);

  Page<OrderShipment> findBySellerIdAndStatusNot(
      UUID sellerId, OrderStatus status, Pageable pageable);
}
