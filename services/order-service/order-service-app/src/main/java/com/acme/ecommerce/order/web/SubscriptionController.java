package com.acme.ecommerce.order.web;

import com.acme.ecommerce.order.service.SubscriptionService;
import com.acme.ecommerce.order.service.SubscriptionService.CreateRequest;
import com.acme.ecommerce.order.service.SubscriptionService.Subscription;
import com.acme.ecommerce.order.service.SubscriptionService.UpdateRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Subscribe and Save for the calling user. */
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

  private final SubscriptionService service;

  public SubscriptionController(SubscriptionService service) {
    this.service = service;
  }

  @GetMapping
  public List<Subscription> list(@AuthenticationPrincipal Jwt jwt) {
    return service.list(UUID.fromString(jwt.getSubject()));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Subscription create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRequest request) {
    return service.create(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"), request);
  }

  @PutMapping("/{id}")
  public Subscription update(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody UpdateRequest request) {
    return service.update(UUID.fromString(jwt.getSubject()), id, request);
  }

  @PostMapping("/{id}/skip")
  public Subscription skip(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.skip(UUID.fromString(jwt.getSubject()), id);
  }
}
