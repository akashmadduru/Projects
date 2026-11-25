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

  /** Rows at or below their own low-stock threshold (admin dashboard tile, TD-ADM-04). */
  @org.springframework.data.jpa.repository.Query(
      "select i from Inventory i where i.quantity <= i.lowStockThreshold")
  org.springframework.data.domain.Page<Inventory> findLowStock(
      org.springframework.data.domain.Pageable pageable);

  Optional<Inventory> findByProductId(UUID productId);

  /** Row-locked read, ordered by product id so concurrent reservations lock in the same order. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<Inventory> findByProductIdInOrderByProductId(Collection<UUID> productIds);
}
