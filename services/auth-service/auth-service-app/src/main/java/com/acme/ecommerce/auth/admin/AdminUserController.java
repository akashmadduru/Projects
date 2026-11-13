package com.acme.ecommerce.auth.admin;

import com.acme.ecommerce.auth.admin.AdminUserDtos.AdminUserResponse;
import com.acme.ecommerce.auth.admin.AdminUserDtos.AdminUserStats;
import com.acme.ecommerce.auth.admin.AdminUserDtos.RolesChangeRequest;
import com.acme.ecommerce.auth.admin.AdminUserDtos.StatusChangeRequest;
import com.acme.ecommerce.auth.admin.AdminUserDtos.TargetStatus;
import com.acme.ecommerce.auth.audit.AuthAuditPublisher;
import com.acme.ecommerce.auth.domain.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
  private final AuthAuditPublisher audit;

  public AdminUserController(AdminUserService service, AuthAuditPublisher audit) {
    this.service = service;
    this.audit = audit;
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
      HttpServletRequest http,
      @PathVariable UUID id,
      @Valid @RequestBody StatusChangeRequest request) {
    return applyStatus(jwt, http, id, request.status());
  }

  @PostMapping("/{id}/disable")
  public AdminUserResponse disable(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest http, @PathVariable UUID id) {
    return applyStatus(jwt, http, id, TargetStatus.DISABLED);
  }

  @PostMapping("/{id}/enable")
  public AdminUserResponse enable(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest http, @PathVariable UUID id) {
    return applyStatus(jwt, http, id, TargetStatus.ACTIVE);
  }

  private AdminUserResponse applyStatus(
      Jwt jwt, HttpServletRequest http, UUID id, TargetStatus target) {
    String before = service.get(id).status();
    AdminUserResponse after = service.changeStatus(actor(jwt), id, target);
    audit.publish(
        jwt,
        http.getRemoteAddr(),
        target == TargetStatus.DISABLED ? "USER_DISABLE" : "USER_ENABLE",
        "USER",
        id.toString(),
        "{\"status\":" + AuthAuditPublisher.str(before) + "}",
        "{\"status\":" + AuthAuditPublisher.str(after.status()) + "}");
    return after;
  }

  @PutMapping("/{id}/roles")
  public AdminUserResponse changeRoles(
      @AuthenticationPrincipal Jwt jwt,
      HttpServletRequest http,
      @PathVariable UUID id,
      @Valid @RequestBody RolesChangeRequest request) {
    java.util.List<String> before = service.get(id).roles();
    AdminUserResponse after = service.changeRoles(actor(jwt), id, request.roles());
    audit.publish(
        jwt,
        http.getRemoteAddr(),
        "USER_ROLES_CHANGE",
        "USER",
        id.toString(),
        "{\"roles\":" + AuthAuditPublisher.strArray(before) + "}",
        "{\"roles\":" + AuthAuditPublisher.strArray(after.roles()) + "}");
    return after;
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
