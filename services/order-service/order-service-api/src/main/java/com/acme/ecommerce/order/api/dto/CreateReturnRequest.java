package com.acme.ecommerce.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateReturnRequest(
    @NotEmpty @Size(max = 50) List<@Valid ReturnItemRequest> items,
    @NotNull ReasonRequest reason,
    @Size(max = 1000) String comment) {

  public record ReturnItemRequest(@NotNull UUID productId, @Min(1) @Max(99) int quantity) {}

  public enum ReasonRequest {
    DAMAGED,
    WRONG_ITEM,
    NOT_AS_DESCRIBED,
    NO_LONGER_NEEDED,
    OTHER
  }
}
