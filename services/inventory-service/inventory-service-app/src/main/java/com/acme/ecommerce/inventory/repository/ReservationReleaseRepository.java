package com.acme.ecommerce.inventory.repository;

import com.acme.ecommerce.inventory.domain.ReservationRelease;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationReleaseRepository extends JpaRepository<ReservationRelease, UUID> {
  boolean existsByOrderIdAndReleaseKey(UUID orderId, String releaseKey);
}
