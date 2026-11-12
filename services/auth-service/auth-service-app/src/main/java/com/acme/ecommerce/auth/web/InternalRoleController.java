package com.acme.ecommerce.auth.web;

import com.acme.ecommerce.auth.domain.RoleEntity;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.repository.RoleRepository;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.common.security.Roles;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service role grant (X-Internal-Token, see InternalSecurityConfig). Only roles on the
 * allow-list below can be granted this way - never ADMIN.
 */
@RestController
public class InternalRoleController {

  private static final Logger log = LoggerFactory.getLogger(InternalRoleController.class);
  private static final Set<String> GRANTABLE = Set.of(Roles.SELLER);

  public record GrantRoleRequest(String role) {}

  private final UserAccountRepository users;
  private final RoleRepository roles;

  public InternalRoleController(UserAccountRepository users, RoleRepository roles) {
    this.users = users;
    this.roles = roles;
  }

  @PostMapping("/internal/users/{userId}/roles")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  public void grant(@PathVariable UUID userId, @RequestBody GrantRoleRequest request) {
    String bare =
        request.role() == null ? "" : request.role().trim().toUpperCase().replaceFirst("^ROLE_", "");
    if (!GRANTABLE.contains(bare)) {
      throw new ValidationException("Role cannot be granted internally: " + request.role());
    }
    UserAccount user =
        users
            .findWithRolesById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    RoleEntity role =
        roles
            .findByName(Roles.ROLE_PREFIX + bare)
            .orElseThrow(() -> new ResourceNotFoundException("Role", bare));
    user.grant(role);
    log.info("Granted {} to user {}", bare, userId);
  }
}
