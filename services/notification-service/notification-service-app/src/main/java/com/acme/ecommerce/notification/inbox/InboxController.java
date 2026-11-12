package com.acme.ecommerce.notification.inbox;

import com.acme.ecommerce.notification.inbox.InboxService.Page;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class InboxController {

  private final InboxService inbox;

  public InboxController(InboxService inbox) {
    this.inbox = inbox;
  }

  @GetMapping
  public Page list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "false") boolean unreadOnly) {
    return inbox.list(uid(jwt), page, size, unreadOnly);
  }

  @PostMapping("/{id}/read")
  public Map<String, Long> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return Map.of("unreadCount", inbox.markRead(uid(jwt), id));
  }

  @PostMapping("/read-all")
  public Map<String, Long> readAll(@AuthenticationPrincipal Jwt jwt) {
    return Map.of("unreadCount", inbox.readAll(uid(jwt)));
  }

  private static UUID uid(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
