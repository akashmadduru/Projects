package com.acme.ecommerce.auth.admin;

import com.acme.ecommerce.auth.admin.AdminUserDtos.AdminUserResponse;
import com.acme.ecommerce.auth.admin.AdminUserDtos.AdminUserStats;
import com.acme.ecommerce.auth.admin.AdminUserDtos.TargetStatus;
import com.acme.ecommerce.auth.domain.RoleEntity;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserStatus;
import com.acme.ecommerce.auth.repository.RoleRepository;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.token.RevokeReason;
import com.acme.ecommerce.auth.token.TokenService;
import com.acme.ecommerce.common.core.error.DomainRuleViolationException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.common.security.Roles;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Back-office user management: search, enable/disable, role assignment.
 *
 * <p>SAFETY RULES (enforced here, not in the SPA):
 *
 * <ul>
 *   <li>An admin cannot change their own status or roles - prevents accidental self-lockout and
 *       makes privilege changes always a two-person action.
 *   <li>The last ACTIVE admin can be neither disabled nor demoted. ROLE_ADMIN is row-locked for
 *       the transaction so two concurrent changes cannot both pass this check.
 *   <li>Disabling a user or removing any role revokes every refresh token, so the change takes
 *       effect no later than the access-token TTL.
 * </ul>
 */
@Service
public class AdminUserService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);
  private static final Set<String> ASSIGNABLE =
      Set.of(Roles.ADMIN, Roles.CATALOG_MANAGER, Roles.VIEWER, Roles.SELLER);

  private final UserAccountRepository users;
  private final RoleRepository roles;
  private final TokenService tokens;

  public AdminUserService(UserAccountRepository users, RoleRepository roles, TokenService tokens) {
    this.users = users;
    this.roles = roles;
    this.tokens = tokens;
  }

  @Transactional(readOnly = true)
  public Page<AdminUserResponse> list(
      String term, UserStatus status, String role, Pageable pageable) {
    String q = term == null ? "" : term.trim().toLowerCase(Locale.ROOT).replaceAll("[%_\\\\]", "");
    String roleName = role == null || role.isBlank() ? null : prefixedAssignable(role);
    Specification<UserAccount> spec =
        (root, query, cb) -> {
          List<Predicate> ps = new ArrayList<>();
          if (!q.isEmpty()) {
            ps.add(
                cb.or(
                    cb.like(cb.lower(root.get("email")), "%" + q + "%"),
                    cb.like(cb.lower(root.get("displayName")), "%" + q + "%")));
          }
          if (status != null) {
            ps.add(cb.equal(root.get("status"), status));
          }
          if (roleName != null) {
            Join<UserAccount, RoleEntity> r = root.join("roles");
            ps.add(cb.equal(r.get("name"), roleName));
          }
          return cb.and(ps.toArray(new Predicate[0]));
        };
    Page<UserAccount> page = users.findAll(spec, pageable);
    Map<UUID, UserAccount> withRoles =
        users.findWithRolesByIdIn(page.map(UserAccount::getId).getContent()).stream()
            .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
    return page.map(u -> toResponse(withRoles.getOrDefault(u.getId(), u)));
  }

  @Transactional(readOnly = true)
  public AdminUserResponse get(UUID id) {
    return toResponse(load(id));
  }

  @Transactional(readOnly = true)
  public AdminUserStats stats() {
    Map<String, Long> byStatus = new LinkedHashMap<>();
    for (UserStatus s : UserStatus.values()) {
      byStatus.put(s.name(), 0L);
    }
    long total = 0;
    for (Object[] row : users.countGroupedByStatus()) {
      long n = (Long) row[1];
      byStatus.put(((UserStatus) row[0]).name(), n);
      total += n;
    }
    return new AdminUserStats(total, byStatus);
  }

  @Transactional
  public AdminUserResponse changeStatus(UUID actorId, UUID id, TargetStatus target) {
    forbidSelf(actorId, id);
    lockAdminRole();
    UserAccount user = load(id);
    if (target == TargetStatus.DISABLED) {
      if (user.getStatus() == UserStatus.ACTIVE && hasRole(user, Roles.ROLE_ADMIN)) {
        ensureAnotherActiveAdmin();
      }
      user.disable();
      tokens.revokeAllSessions(user.getId(), RevokeReason.ADMIN);
    } else {
      user.enable();
    }
    log.info("Admin {} set status of user {} to {}", actorId, id, target);
    return toResponse(user);
  }

  @Transactional
  public AdminUserResponse changeRoles(UUID actorId, UUID id, Set<String> requested) {
    forbidSelf(actorId, id);
    RoleEntity adminRole = lockAdminRole();
    Set<String> wanted = requested.stream().map(this::prefixedAssignable).collect(Collectors.toSet());
    UserAccount user = load(id);

    boolean losesAdmin = hasRole(user, Roles.ROLE_ADMIN) && !wanted.contains(Roles.ROLE_ADMIN);
    if (losesAdmin && user.getStatus() == UserStatus.ACTIVE) {
      ensureAnotherActiveAdmin();
    }

    boolean removedAny = false;
    for (RoleEntity current : List.copyOf(user.getRoles())) {
      if (!wanted.contains(current.getName())) {
        user.revoke(current);
        removedAny = true;
      }
    }
    for (String name : wanted) {
      RoleEntity role =
          name.equals(Roles.ROLE_ADMIN)
              ? adminRole
              : roles
                  .findByName(name)
                  .orElseThrow(() -> new ResourceNotFoundException("Role", name));
      user.grant(role);
    }
    if (removedAny) {
      tokens.revokeAllSessions(user.getId(), RevokeReason.ADMIN);
    }
    log.info("Admin {} set roles of user {} to {}", actorId, id, wanted);
    return toResponse(user);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private UserAccount load(UUID id) {
    return users.findWithRolesById(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
  }

  private RoleEntity lockAdminRole() {
    return roles
        .lockByName(Roles.ROLE_ADMIN)
        .orElseThrow(() -> new IllegalStateException("ROLE_ADMIN missing from reference data"));
  }

  private void ensureAnotherActiveAdmin() {
    if (users.countByRoleAndStatus(Roles.ROLE_ADMIN, UserStatus.ACTIVE) <= 1) {
      throw new DomainRuleViolationException(
          "The last active administrator cannot be disabled or demoted.", "LAST_ADMIN");
    }
  }

  private static void forbidSelf(UUID actorId, UUID targetId) {
    if (actorId != null && actorId.equals(targetId)) {
      throw new DomainRuleViolationException(
          "You cannot change your own status or roles.", "SELF_MODIFICATION");
    }
  }

  private String prefixedAssignable(String role) {
    String upper = role.trim().toUpperCase(Locale.ROOT);
    String bare = upper.startsWith(Roles.ROLE_PREFIX) ? upper.substring(5) : upper;
    if (!ASSIGNABLE.contains(bare)) {
      throw new ValidationException("Unknown role: " + role);
    }
    return Roles.ROLE_PREFIX + bare;
  }

  private static boolean hasRole(UserAccount user, String prefixed) {
    return user.getRoles().stream().anyMatch(r -> prefixed.equals(r.getName()));
  }

  private static AdminUserResponse toResponse(UserAccount u) {
    return new AdminUserResponse(
        u.getId(),
        u.getEmail(),
        u.getDisplayName(),
        u.getStatus().name(),
        u.getRoles().stream().map(RoleEntity::claimName).sorted().toList(),
        u.isEmailVerified(),
        u.getPhone(),
        u.isPhoneVerified(),
        u.getCreatedAt(),
        u.getUpdatedAt());
  }
}
