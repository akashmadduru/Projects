package com.acme.ecommerce.cart.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record WishlistMergeItem(
    @NotNull UUID productId,
    @NotBlank @Size(max = 300) String name,
    @NotNull @DecimalMin("0.00") @DecimalMax("999999999999999") @Digits(integer = 15, fraction = 4)
        BigDecimal price,
    @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter currency code")
        String currency,
    @Size(max = 2048) @Pattern(regexp = "^https?://.*", message = "must be an http(s) URL")
        String imageUrl,
    UUID variantId,
    @Size(max = 200) String variantLabel) {}
