package com.acme.ecommerce.inventory.web.internal;

import com.acme.ecommerce.inventory.service.ReservationService;
import com.acme.ecommerce.inventory.service.ReservationService.Line;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only: never routed by the gateway, guarded by X-Internal-Token. */
@RestController
@RequestMapping("/internal/inventory/reservations")
public class InternalReservationController {

  public record ItemDto(@NotNull UUID productId, @Min(1) int quantity) {}

  public record ReserveRequest(@NotNull UUID orderId, @NotEmpty List<@Valid ItemDto> items) {}

  public record ReleaseRequest(UUID returnId, @NotEmpty List<@Valid ItemDto> items) {}

  private final ReservationService reservations;

  public InternalReservationController(ReservationService reservations) {
    this.reservations = reservations;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reserve(@Valid @RequestBody ReserveRequest request) {
    reservations.reserve(request.orderId(), lines(request.items()));
  }

  @DeleteMapping("/{orderId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void restore(@PathVariable UUID orderId) {
    reservations.restoreAll(orderId);
  }

  @PostMapping("/{orderId}/release")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable UUID orderId, @Valid @RequestBody ReleaseRequest request) {
    reservations.release(
        orderId,
        request.returnId() == null ? null : request.returnId().toString(),
        lines(request.items()));
  }

  private static List<Line> lines(List<ItemDto> items) {
    return items.stream().map(i -> new Line(i.productId(), i.quantity())).toList();
  }
}
