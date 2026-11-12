package com.acme.ecommerce.cart.alert;

import com.acme.ecommerce.cart.alert.AlertRepository.Alert;
import com.acme.ecommerce.cart.alert.AlertService.CreateAlert;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The calling user's price-drop / back-in-stock alerts. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

  public record AlertRequest(
      @NotNull UUID productId,
      UUID variantId,
      @NotNull String type,
      BigDecimal targetPrice,
      BigDecimal currentPrice,
      String currency,
      String productName) {}

  private final AlertService service;

  public AlertController(AlertService service) {
    this.service = service;
  }

  @GetMapping
  public List<Alert> list(@AuthenticationPrincipal Jwt jwt) {
    return service.list(UUID.fromString(jwt.getSubject()));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Alert create(@AuthenticationPrincipal Jwt jwt, @RequestBody AlertRequest r) {
    return service.create(
        UUID.fromString(jwt.getSubject()),
        jwt.getClaimAsString("email"),
        new CreateAlert(
            r.productId(),
            r.variantId(),
            r.type(),
            r.targetPrice(),
            r.currentPrice(),
            r.currency(),
            r.productName()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.delete(UUID.fromString(jwt.getSubject()), id);
  }
}
