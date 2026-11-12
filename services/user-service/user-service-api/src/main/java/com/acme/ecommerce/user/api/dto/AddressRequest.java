package com.acme.ecommerce.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Create / full-replace payload for a shipping address. {@code country} is ISO 3166-1 alpha-2. */
public record AddressRequest(
    @Size(max = 60) String label,
    @NotBlank @Size(max = 120) String fullName,
    @Size(max = 32) String phone,
    @NotBlank @Size(max = 200) String line1,
    @Size(max = 200) String line2,
    @NotBlank @Size(max = 100) String city,
    @Size(max = 100) String state,
    @NotBlank @Size(max = 20) String postalCode,
    @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO 3166-1 alpha-2 code")
        String country) {}
