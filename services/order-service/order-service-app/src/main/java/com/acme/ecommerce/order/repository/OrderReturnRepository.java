package com.acme.ecommerce.order.repository;

import com.acme.ecommerce.order.domain.OrderReturn;
import com.acme.ecommerce.order.domain.ReturnStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, UUID> {

  Page<OrderReturn> findByStatus(ReturnStatus status, Pageable pageable);
}
