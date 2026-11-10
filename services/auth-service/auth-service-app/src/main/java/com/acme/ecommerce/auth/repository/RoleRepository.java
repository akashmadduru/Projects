package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.RoleEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
