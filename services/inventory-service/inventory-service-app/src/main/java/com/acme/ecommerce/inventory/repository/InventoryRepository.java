package com.acme.ecommerce.inventory.repository;

import com.acme.ecommerce.inventory.domain.Inventory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Inventory}.
 *
 * <p>{@code findByProductId} exists for two callers: {@link
 * com.acme.ecommerce.inventory.service.InventoryService#create} (duplicate-productId check) and
 * {@link com.acme.ecommerce.inventory.service.InventoryService#provisionWithQuantity} / {@link
 * com.acme.ecommerce.inventory.service.InventoryService#removeIfPresent} (the Kafka consumer's
 * idempotent provision/retire logic). Backed by the {@code UNIQUE(product_id)} index, so it is
 * never a sequential scan.
 *
 * <p>NO {@code @Transactional} HERE. The boundary lives on the service, which is the only layer
 * that knows what constitutes a unit of work.
 */
public interface InventoryRepository extends JpaRepository<Inventory, UUID> {
  Optional<Inventory> findByProductId(UUID productId);
}