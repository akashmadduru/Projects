package com.acme.ecommerce.auth.repository;

import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
public interface UserAccountRepository
    extends JpaRepository<UserAccount, UUID>, JpaSpecificationExecutor<UserAccount> {

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

  /** The account that owns a VERIFIED phone number (at most one, by partial unique index). */
  @EntityGraph(attributePaths = "roles")
  Optional<UserAccount> findWithRolesByPhoneAndPhoneVerifiedTrue(String phone);

  boolean existsByPhoneAndPhoneVerifiedTrueAndIdNot(String phone, UUID id);

  /**
   * Batch role load for the admin user list: the page itself is fetched without roles (a
   * collection fetch join cannot be paginated in SQL), then this loads roles for the page's ids
   * in one query.
   */
  @EntityGraph(attributePaths = "roles")
  List<UserAccount> findWithRolesByIdIn(Collection<UUID> ids);

  /** Active holders of a role (PREFIXED name). Used by the last-admin guard. */
  @Query(
      "select count(distinct u) from UserAccount u join u.roles r"
          + " where r.name = :roleName and u.status = :status")
  long countByRoleAndStatus(
      @Param("roleName") String roleName, @Param("status") UserStatus status);

  /** Rows of [UserStatus, Long] for the admin dashboard. */
  @Query("select u.status, count(u) from UserAccount u group by u.status")
  List<Object[]> countGroupedByStatus();
}
