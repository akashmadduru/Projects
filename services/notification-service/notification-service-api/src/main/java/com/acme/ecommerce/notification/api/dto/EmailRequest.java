package com.acme.ecommerce.notification.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** Request to send one templated email. */
public record EmailRequest(
    @NotBlank @Email String to, @NotNull EmailTemplate template, Map<String, String> data) {}
