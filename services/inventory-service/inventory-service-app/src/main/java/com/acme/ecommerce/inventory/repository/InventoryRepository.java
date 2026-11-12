package com.acme.ecommerce.inventory.repository;

import com.acme.ecommerce.inventory.domain.Inventory;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {
  Optional<Inventory> findByProductId(UUID productId);

  /** Row-locked read, ordered by product id so concurrent reservations lock in the same order. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<Inventory> findByProductIdInOrderByProductId(Collection<UUID> productIds);
}
