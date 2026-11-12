package com.acme.ecommerce.user.api.dto;

import java.time.Instant;
import java.util.UUID;

/** The caller's profile. */
public record UserProfileResponse(
    UUID userId, String email, String displayName, String phone, Instant createdAt) {}
