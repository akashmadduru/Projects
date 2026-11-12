package com.acme.ecommerce.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of the admin order/return endpoints. */
public final class AdminRequests {

  private AdminRequests() {}

  public enum TargetStatus {
    CONFIRMED,
    PACKED,
    SHIPPED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    PLACED,
    PENDING_PAYMENT,
    CANCELLED
  }

  public record StatusUpdate(
      @NotNull TargetStatus status,
      @Size(max = 500) String note,
      @Size(max = 100) String trackingNumber,
      @Size(max = 100) String carrier) {}

  public record CancelOrder(@NotBlank @Size(max = 500) String reason) {}

  public record ReturnDecision(@NotNull Boolean approve, @Size(max = 1000) String note) {}

  public enum ReturnTarget {
    PICKED_UP,
    REFUNDED
  }

  public record ReturnStatusUpdate(@NotNull ReturnTarget status) {}
}
