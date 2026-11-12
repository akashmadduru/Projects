package com.acme.ecommerce.cart.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** A cart line as sent by the client (add / merge). Price is a display snapshot only. */
public record CartItemRequest(
    @NotNull UUID productId,
    @Min(1) @Max(99) int quantity,
    @NotBlank @Size(max = 300) String name,
    @NotNull @DecimalMin("0.00") @DecimalMax("999999999999999") @Digits(integer = 15, fraction = 4)
        BigDecimal unitPrice,
    @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter currency code")
        String currency,
    @Size(max = 2048) @Pattern(regexp = "^https?://.*", message = "must be an http(s) URL")
        String imageUrl) {}
