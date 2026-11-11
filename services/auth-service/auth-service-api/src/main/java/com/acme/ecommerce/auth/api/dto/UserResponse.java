package com.acme.ecommerce.auth.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user as exposed to callers (GET /api/auth/me). Roles carry unprefixed names matching the access
 * token claims.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {
  UUID id;
  String email;
  boolean emailVerified;
  String displayName;
  String status;
  List<String> roles;
  Instant createdAt;
}
