package com.acme.ecommerce.product.repository;

import com.acme.ecommerce.product.domain.Category;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link Category}.
 *
 * <p>Empty on purpose, mirroring {@link ProductRepository}. {@code findById} and {@code
 * findAll}/{@code save} from {@link JpaRepository} are all this pass needs.
 *
 * <p>NO {@code @Transactional} HERE - the boundary lives on the service.
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

  /** Delete guard: a category with subcategories cannot be removed (self-referencing FK). */
  boolean existsByParentCategoryId(UUID parentCategoryId);

  /**
   * Delete guard: products reference categories by a plain FK column, not a JPA association, so
   * this is native SQL rather than a derived query.
   */
  @Query(
      value = "SELECT count(*) FROM product.products WHERE category_id = :id",
      nativeQuery = true)
  long countProductsInCategory(@Param("id") UUID id);
}
