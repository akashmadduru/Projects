package com.acme.ecommerce.order.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** A Buy Now line. */
public record OrderLineRequest(@NotNull UUID productId, @Min(1) @Max(99) int quantity) {}
