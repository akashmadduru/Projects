package com.acme.ecommerce.cart.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WishlistMergeRequest(
    @NotNull @Size(max = 200) List<@Valid WishlistMergeItem> items) {}
