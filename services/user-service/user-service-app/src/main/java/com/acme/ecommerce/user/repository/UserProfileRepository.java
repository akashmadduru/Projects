package com.acme.ecommerce.user.repository;

import com.acme.ecommerce.user.domain.UserProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

  /** Transaction-scoped advisory lock; serialises per-user address writes. */
  @Query(value = "SELECT CAST(pg_advisory_xact_lock(:key) AS text)", nativeQuery = true)
  String lockUser(@Param("key") long key);

  /**
   * Race-safe lazy create, and refresh of the email snapshot when the token's email claim changed
   * (TD-BE-14). A null claim never erases a stored email; an unchanged email writes nothing.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO users.user_profiles (user_id, email, created_at, updated_at, version) "
              + "VALUES (:userId, :email, now(), now(), 0) ON CONFLICT (user_id) DO UPDATE "
              + "SET email = EXCLUDED.email, updated_at = now(), "
              + "version = users.user_profiles.version + 1 "
              + "WHERE EXCLUDED.email IS NOT NULL "
              + "AND users.user_profiles.email IS DISTINCT FROM EXCLUDED.email",
      nativeQuery = true)
  void upsertEmail(@Param("userId") UUID userId, @Param("email") String email);
}
