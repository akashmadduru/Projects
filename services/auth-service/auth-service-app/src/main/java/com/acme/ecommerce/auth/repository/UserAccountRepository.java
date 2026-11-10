package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link UserAccount}.
 *
 * <p>N+1 POLICY: {@code UserAccount.roles} is {@code LAZY}, so the plain finders below load a user
 * WITHOUT touching {@code user_roles} or {@code roles} - which is what the paths that do not need
 * roles should do. The paths that DO need them ({@code /me}, login, refresh) use the {@code
 * findWithRoles*} variants, whose {@link EntityGraph} turns three round trips into one LEFT JOIN.
 * Calling a plain finder and then reading {@code getRoles()} would work, silently, and issue an
 * extra query per user - the classic way an endpoint that is fine with one user becomes unusable on
 * a list of fifty.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

  /**
   * Looks up by email.
   *
   * <p>The argument MUST already be normalised with {@code UserAccount.normaliseEmail}. The column
   * is {@code citext}, but a JDBC {@code varchar} parameter compared against it resolves through
   * the implicit {@code citext -> text} cast to a case-SENSITIVE comparison, so the database will
   * not save a caller who skipped normalisation.
   */
  Optional<UserAccount> findByEmail(String email);

  /** As {@link #findByEmail}, with roles fetched in the same query. */
  @EntityGraph(attributePaths = "roles")
  Optional<UserAccount> findWithRolesByEmail(String email);

  /** Single-query load of a user and their roles. Used by {@code /me} and by refresh rotation. */
  @EntityGraph(attributePaths = "roles")
  Optional<UserAccount> findWithRolesById(UUID id);

  /** Existence check for registration. Cheaper than loading the row we are about to reject. */
  boolean existsByEmail(String email);
}
