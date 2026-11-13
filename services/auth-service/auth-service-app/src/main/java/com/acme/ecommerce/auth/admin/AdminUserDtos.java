package com.acme.ecommerce.auth.admin;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Wire shapes for the back-office user-management API ({@code /api/admin/users}). */
public final class AdminUserDtos {

  private AdminUserDtos() {}

  /** Roles are UNPREFIXED ({@code ADMIN}), matching the JWT {@code roles} claim. */
  public record AdminUserResponse(
      UUID id,
      String email,
      String displayName,
      String status,
      List<String> roles,
      boolean emailVerified,
      String phone,
      boolean phoneVerified,
      Instant createdAt,
      Instant updatedAt) {}

  /** Only ACTIVE and DISABLED are settable; LOCKED is owned by the login-attempt policy. */
  public record StatusChangeRequest(@NotNull TargetStatus status) {}

  public enum TargetStatus {
    ACTIVE,
    DISABLED
  }

  /** Replaces the user's role set. Unprefixed names; at least one role. */
  public record RolesChangeRequest(@NotEmpty @Size(max = 6) Set<@NotNull String> roles) {}

  public record AdminUserStats(long total, Map<String, Long> byStatus) {}
}
