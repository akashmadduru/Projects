package com.acme.ecommerce.product.repository;

import com.acme.ecommerce.product.domain.Offer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OfferRepository extends JpaRepository<Offer, UUID> {

  List<Offer> findByProductIdAndActiveTrue(UUID productId);

  List<Offer> findByProductId(UUID productId);

  List<Offer> findBySellerIdOrderByCreatedAtDesc(UUID sellerId);

  Optional<Offer> findByProductIdAndVariantIdIsNullAndSellerId(UUID productId, UUID sellerId);

  Optional<Offer> findByProductIdAndVariantIdAndSellerId(
      UUID productId, UUID variantId, UUID sellerId);

  @Query("select distinct o.productId from Offer o where o.sellerId = :sellerId and o.active = true")
  List<UUID> findProductIdsBySeller(UUID sellerId);

  @Query("select o from Offer o where o.productId in :ids and o.active = true")
  List<Offer> findActiveByProductIds(Collection<UUID> ids);
}
