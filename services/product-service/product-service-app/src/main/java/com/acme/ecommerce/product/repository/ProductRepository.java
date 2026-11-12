package com.acme.ecommerce.product.repository;

import com.acme.ecommerce.product.domain.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository
    extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

  boolean existsBySlug(String slug);

  Optional<Product> findBySlug(String slug);

  Optional<Product> findBySku(String sku);
}
