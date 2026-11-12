package com.acme.ecommerce.user.api.dto;

import jakarta.validation.constraints.Size;

/** Full replace of the editable profile fields. */
public record UpdateProfileRequest(
    @Size(max = 120) String displayName, @Size(max = 32) String phone) {}
