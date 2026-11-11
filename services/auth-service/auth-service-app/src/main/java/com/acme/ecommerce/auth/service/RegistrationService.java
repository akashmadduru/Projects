package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.RegisterRequest;
import com.acme.ecommerce.auth.api.dto.UserResponse;
import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.domain.RoleEntity;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserCredential;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.repository.RoleRepository;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.repository.UserCredentialRepository;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.common.security.Roles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service sign-up.
 *
 * <p>OFF BY DEFAULT ({@code auth.registration.enabled=false}). This is the identity service for an
 * internal admin console; accounts are provisioned deliberately. An open registration endpoint on
 * an internal tool is a way for anyone who can reach the network to obtain a valid, signed token
 * for the platform - even a {@code VIEWER} one, which is enough to enumerate the catalog API.
 *
 * <p>ROLE IS HARDCODED TO {@code ROLE_VIEWER} and is not derived from the request in any way. There
 * is no request field for it, no "admin" flag, and no configuration that can raise it. Elevation is
 * an administrative action.
 *
 * <p>DUPLICATE-EMAIL DISCLOSURE: returning 409 {@code AUTH_EMAIL_TAKEN} tells the caller that an
 * address is registered. That is an accepted trade-off, agreed for an internal console: the
 * alternative (always returning 201 and sending a "someone tried to register your address" email)
 * needs an email pipeline that does not exist yet, and produces a genuinely baffling UX in the
 * meantime. Revisit if this endpoint is ever exposed publicly.
 */
@Service
public class RegistrationService {

  private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

  private final UserAccountRepository users;
  private final UserCredentialRepository credentials;
  private final RoleRepository roles;
  private final PasswordEncoder passwordEncoder;
  private final UserPrincipalService principals;
  private final TimeProvider timeProvider;
  private final AuthProperties.Registration config;

  public RegistrationService(
      UserAccountRepository users,
      UserCredentialRepository credentials,
      RoleRepository roles,
      PasswordEncoder passwordEncoder,
      UserPrincipalService principals,
      TimeProvider timeProvider,
      AuthProperties properties) {
    this.users = users;
    this.credentials = credentials;
    this.roles = roles;
    this.passwordEncoder = passwordEncoder;
    this.principals = principals;
    this.timeProvider = timeProvider;
    this.config = properties.registration();
  }

  /**
   * Creates a VIEWER account.
   *
   * <p>One transaction: the user row, its role grant and its credential row are meaningless
   * individually. A user with no credential could never log in; a credential with no user violates
   * the foreign key.
   */
  @Transactional
  public UserResponse register(RegisterRequest request) {
    if (!config.enabled()) {
      throw new AuthException(
          AuthErrorCode.AUTH_REGISTRATION_DISABLED,
          "Registration attempted while auth.registration.enabled=false");
    }

    String email = UserAccount.normaliseEmail(request.getEmail());
    if (users.existsByEmail(email)) {
      throw new AuthException(AuthErrorCode.AUTH_EMAIL_TAKEN, "Email already registered: " + email);
    }

    RoleEntity viewer =
        roles
            .findByName(Roles.ROLE_VIEWER)
            .orElseThrow(
                () ->
                    // Seeded by V1__auth_baseline.sql. Absent means the migration
                    // did not run, which is a deployment fault, not a user error.
                    new IllegalStateException(
                        "Role " + Roles.ROLE_VIEWER + " is missing; check Flyway migrations"));

    UserAccount user = UserAccount.create(email, request.getDisplayName(), false);
    user.grant(viewer);

    try {
      users.saveAndFlush(user);
      credentials.save(
          UserCredential.create(
              user.getId(), passwordEncoder.encode(request.getPassword()), timeProvider.now()));
    } catch (DataIntegrityViolationException e) {
      // The existsByEmail check above is advisory only: two concurrent
      // registrations for the same address both pass it and one loses at the
      // UNIQUE index. The constraint is the real guard; this turns its raw
      // failure into the same 409 the pre-check would have produced.
      throw new AuthException(
          AuthErrorCode.AUTH_EMAIL_TAKEN, "Concurrent registration for " + email);
    }

    log.info("Registered userId={} email={} role={}", user.getId(), email, Roles.ROLE_VIEWER);
    return principals.toResponse(user);
  }
}
