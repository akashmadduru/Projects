package com.acme.ecommerce.notification.push;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/push")
public class PushController {

  public record Keys(@NotBlank String p256dh, @NotBlank String auth) {}

  public record SubscribeRequest(@NotBlank String endpoint, @NotNull @Valid Keys keys) {}

  public record UnsubscribeRequest(@NotBlank String endpoint) {}

  private final PushService push;

  public PushController(PushService push) {
    this.push = push;
  }

  @GetMapping("/vapid-public-key")
  public Map<String, String> vapidKey() {
    String key = push.publicKey();
    if (key == null) {
      throw new CodedException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Web push is not configured");
    }
    return Map.of("publicKey", key);
  }

  @PostMapping("/subscriptions")
  @ResponseStatus(HttpStatus.CREATED)
  public void subscribe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubscribeRequest r) {
    push.subscribe(
        UUID.fromString(jwt.getSubject()), r.endpoint(), r.keys().p256dh(), r.keys().auth());
  }

  @DeleteMapping("/subscriptions")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unsubscribe(@AuthenticationPrincipal Jwt jwt, @RequestBody UnsubscribeRequest r) {
    push.unsubscribe(UUID.fromString(jwt.getSubject()), r.endpoint());
  }
}
