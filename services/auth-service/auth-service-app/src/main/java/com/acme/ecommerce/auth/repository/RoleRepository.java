package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.RoleEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for role reference data.
 *
 * <p>Read-only in practice: the three rows are seeded by the baseline migration. A new role is a
 * migration plus a deploy, not a runtime insert - roles are referenced by authorization rules in
 * other services, so inventing one at runtime produces a role nothing grants access to.
 */
public interface RoleRepository extends JpaRepository<RoleEntity, Short> {

  /**
   * Finds a role by its PREFIXED authority name ({@code ROLE_VIEWER}).
   *
   * <p>Called once per registration/provisioning, both cold paths. If it ever appears on a hot
   * path, cache it - the table has three rows and never changes between deploys.
   */
  Optional<RoleEntity> findByName(String name);

  /**
   * Row-locks a role for the rest of the transaction. The admin user-management service locks
   * ROLE_ADMIN before every status/role change so two concurrent demotions cannot both pass the
   * last-admin check.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RoleEntity r where r.name = :name")
  Optional<RoleEntity> lockByName(@Param("name") String name);
}
