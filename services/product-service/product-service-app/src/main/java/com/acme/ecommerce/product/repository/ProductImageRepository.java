package com.acme.ecommerce.product.repository;

import com.acme.ecommerce.product.domain.ProductImage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ProductImage}.
 *
 * <p>Two derived queries beyond what {@link JpaRepository} gives for free: {@code
 * findByProductIdOrderByDisplayOrderAsc} backs {@code ProductCatalogService.findById}'s "rich"
 * response, and {@code deleteByProductId} backs {@code update}'s replace-semantics for images (see
 * {@code ProductCatalogService} for why replace, not diff, is the deliberate choice here).
 *
 * <p>NO {@code @Transactional} HERE - the boundary lives on the service.
 */
public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

  List<ProductImage> findByProductIdOrderByDisplayOrderAsc(UUID productId);

  void deleteByProductId(UUID productId);
}
