package com.acme.ecommerce.notification.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request to send one SMS (E.164 destination). */
public record SmsRequest(@NotBlank String to, @NotBlank @Size(max = 500) String text) {}
