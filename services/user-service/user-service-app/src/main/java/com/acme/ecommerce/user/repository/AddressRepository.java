package com.acme.ecommerce.user.repository;

import com.acme.ecommerce.user.domain.Address;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AddressRepository extends JpaRepository<Address, UUID> {

  List<Address> findByUserIdOrderByDefaultAddressDescCreatedAtDesc(UUID userId);

  Optional<Address> findByIdAndUserId(UUID id, UUID userId);

  long countByUserId(UUID userId);

  Optional<Address> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

  /** Clears the default flag; run before setting a new default (partial unique index). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update Address a set a.defaultAddress = false "
          + "where a.userId = :userId and a.defaultAddress = true")
  void clearDefault(@Param("userId") UUID userId);
}
