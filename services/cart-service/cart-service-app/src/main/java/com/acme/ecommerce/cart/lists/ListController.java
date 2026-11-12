package com.acme.ecommerce.cart.lists;

import com.acme.ecommerce.cart.lists.ListService.ItemRequest;
import com.acme.ecommerce.cart.lists.ListService.ListView;
import com.acme.ecommerce.cart.lists.ListService.SharedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lists")
public class ListController {

  public record NameRequest(String name) {}

  private final ListService lists;

  public ListController(ListService lists) {
    this.lists = lists;
  }

  @GetMapping
  public List<ListView> list(@AuthenticationPrincipal Jwt jwt) {
    return lists.list(uid(jwt));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ListView create(@AuthenticationPrincipal Jwt jwt, @RequestBody NameRequest request) {
    return lists.create(uid(jwt), request.name());
  }

  /** PUBLIC (see InternalSecurityConfig): shared view by token. Declared before /{id}. */
  @GetMapping("/shared/{token}")
  public SharedList shared(@PathVariable String token) {
    return lists.shared(token);
  }

  @GetMapping("/{id}")
  public ListView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return lists.detail(uid(jwt), id);
  }

  @PutMapping("/{id}")
  public ListView rename(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody NameRequest request) {
    return lists.rename(uid(jwt), id, request.name());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    lists.delete(uid(jwt), id);
  }

  @PostMapping("/{id}/items")
  public ListView addItem(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody ItemRequest request) {
    return lists.addItem(uid(jwt), id, request);
  }

  @DeleteMapping("/{id}/items/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeItem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @PathVariable UUID productId,
      @RequestParam(required = false) UUID variantId) {
    lists.removeItem(uid(jwt), id, productId, variantId);
  }

  @PostMapping("/{id}/share")
  public Map<String, String> share(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    ListView v = lists.share(uid(jwt), id, jwt.getClaimAsString("email"));
    return Map.of("shareUrl", v.shareUrl(), "shareToken", v.shareToken());
  }

  @DeleteMapping("/{id}/share")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unshare(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    lists.unshare(uid(jwt), id);
  }

  private static UUID uid(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
