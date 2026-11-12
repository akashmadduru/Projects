package com.acme.ecommerce.product.repository;

import com.acme.ecommerce.product.domain.ProductVariant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

  List<ProductVariant> findByProductIdOrderBySortOrderAscSkuAsc(UUID productId);

  @Query("select distinct v.productId from ProductVariant v where v.productId in :ids")
  List<UUID> findProductIdsWithVariants(Collection<UUID> ids);
}
