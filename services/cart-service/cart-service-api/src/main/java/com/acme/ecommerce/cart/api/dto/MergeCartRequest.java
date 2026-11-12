package com.acme.ecommerce.cart.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Anonymous (local) cart lines to fold into the server cart after login. */
public record MergeCartRequest(@NotNull @Size(max = 100) List<@Valid CartItemRequest> items) {}
