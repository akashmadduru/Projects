package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/password/forgot. */
public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 320) String email) {}
