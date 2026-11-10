package com.acme.ecommerce.product.repository;

import com.acme.ecommerce.product.domain.Product;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Product}.
 *
 * <p>Empty on purpose. {@code findById} and {@code findAll(Pageable)} come from {@link
 * JpaRepository} and are precisely the two queries Phase 1E needs: a primary-key lookup and one
 * paged scan. Adding derived query methods "for later" would be adding untested, unused SQL.
 *
 * <p>NO {@code @Transactional} HERE. The boundary lives on the service, which is the only layer
 * that knows what constitutes a unit of work. Spring Data's own per-method transactions are
 * sufficient for a repository called inside one.
 */
public interface ProductRepository extends JpaRepository<Product, UUID> {}
