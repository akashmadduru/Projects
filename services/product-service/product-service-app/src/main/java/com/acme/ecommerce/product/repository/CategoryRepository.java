package com.acme.ecommerce.product.repository;

import com.acme.ecommerce.product.domain.Category;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Category}.
 *
 * <p>Empty on purpose, mirroring {@link ProductRepository}. {@code findById} and {@code
 * findAll}/{@code save} from {@link JpaRepository} are all this pass needs.
 *
 * <p>NO {@code @Transactional} HERE - the boundary lives on the service.
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {}
