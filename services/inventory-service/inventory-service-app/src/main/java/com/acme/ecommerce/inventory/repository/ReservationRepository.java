package com.acme.ecommerce.inventory.repository;

import com.acme.ecommerce.inventory.domain.Reservation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
  List<Reservation> findByOrderId(UUID orderId);
}
