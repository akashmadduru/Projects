package com.acme.ecommerce.user.api.dto;

import java.time.Instant;
import java.util.UUID;

/** A saved address: the request fields plus identity, default flag and timestamps. */
public record AddressResponse(
    UUID id,
    String label,
    String fullName,
    String phone,
    String line1,
    String line2,
    String city,
    String state,
    String postalCode,
    String country,
    boolean isDefault,
    Instant createdAt,
    Instant updatedAt) {}
