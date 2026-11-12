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

  /** Race-safe lazy create: concurrent first requests for the same user are both fine. */
  @Modifying
  @Query(
      value =
          "INSERT INTO users.user_profiles (user_id, email, created_at, updated_at, version) "
              + "VALUES (:userId, :email, now(), now(), 0) ON CONFLICT (user_id) DO NOTHING",
      nativeQuery = true)
  void insertIfAbsent(@Param("userId") UUID userId, @Param("email") String email);
}
