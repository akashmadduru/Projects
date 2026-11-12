package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A phone number in E.164 form (e.g. +919876543210). Used by phone add and OTP request. */
public record PhoneRequest(@NotBlank @Size(max = 20) String phone) {}
