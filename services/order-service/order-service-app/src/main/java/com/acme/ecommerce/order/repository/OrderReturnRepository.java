package com.acme.ecommerce.order.repository;

import com.acme.ecommerce.order.domain.OrderReturn;
import com.acme.ecommerce.order.domain.ReturnStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderReturnRepository extends JpaRepository<OrderReturn, UUID> {

  Page<OrderReturn> findByStatus(ReturnStatus status, Pageable pageable);

  /** Rows of [ReturnStatus, Long] for the admin dashboard. */
  @Query("select r.status, count(r) from OrderReturn r group by r.status")
  List<Object[]> countGroupedByStatus();
}
