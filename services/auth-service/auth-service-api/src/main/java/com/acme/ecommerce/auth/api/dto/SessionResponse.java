package com.acme.ecommerce.auth.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
    UUID familyId,
    String device,
    String ipAddress,
    String authMethod,
    Instant lastUsedAt,
    boolean current) {}
