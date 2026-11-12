package com.acme.ecommerce.auth.admin;

import com.acme.ecommerce.auth.admin.AdminUserDtos.AdminUserResponse;
import com.acme.ecommerce.auth.admin.AdminUserDtos.AdminUserStats;
import com.acme.ecommerce.auth.admin.AdminUserDtos.RolesChangeRequest;
import com.acme.ecommerce.auth.admin.AdminUserDtos.StatusChangeRequest;
import com.acme.ecommerce.auth.domain.UserStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Back-office user management. ADMIN only - enforced by the URL rule for {@code /api/admin/**} in
 * {@code SecurityConfig}, so a new method added here is protected without an annotation.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

  private static final int MAX_PAGE_SIZE = 50;

  private final AdminUserService service;

  public AdminUserController(AdminUserService service) {
    this.service = service;
  }

  @GetMapping
  public PagedModel<AdminUserResponse> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UserStatus status,
      @RequestParam(required = false) String role,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PageRequest pageable =
        PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "createdAt"));
    return new PagedModel<>(service.list(q, status, role, pageable));
  }

  @GetMapping("/stats")
  public AdminUserStats stats() {
    return service.stats();
  }

  @GetMapping("/{id}")
  public AdminUserResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PutMapping("/{id}/status")
  public AdminUserResponse changeStatus(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody StatusChangeRequest request) {
    return service.changeStatus(actor(jwt), id, request.status());
  }

  @PutMapping("/{id}/roles")
  public AdminUserResponse changeRoles(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody RolesChangeRequest request) {
    return service.changeRoles(actor(jwt), id, request.roles());
  }

  /** A non-UUID subject cannot be anyone's id, so it can never match the self-check target. */
  private static UUID actor(Jwt jwt) {
    try {
      return jwt == null ? null : UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
