package com.acme.ecommerce.auth.oauth2;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.domain.FederatedIdentity;
import com.acme.ecommerce.auth.domain.RoleEntity;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.auth.repository.FederatedIdentityRepository;
import com.acme.ecommerce.auth.repository.RoleRepository;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.service.UserPrincipalService;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.common.security.Roles;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The four-way decision at the heart of federated login.
 *
 * <pre>
 *   federated_identities row for (provider, sub)?
 *        |
 *        +-- YES ......................... LOGIN. Update last_login_at. Email is irrelevant here:
 *        |                                 the user may have changed their Google address since,
 *        |                                 and it must not affect which local account they get.
 *        |
 *        +-- NO, email_verified = false .. REJECT. Never link, never provision.
 *        |                                 Without this check, anyone who can create an account at
 *        |                                 the IdP claiming victim@example.com immediately owns the
 *        |                                 local victim@example.com account. This one boolean IS the
 *        |                                 account-takeover defence.
 *        |
 *        +-- NO, verified, local user with that email exists .. LINK.
 *        |
 *        +-- NO, verified, no local user ..................... PROVISION (ROLE_VIEWER, no password).
 * </pre>
 *
 * <p>Transactional as a whole: linking or provisioning writes two rows (or three, counting the role
 * grant) that are meaningless apart. A user provisioned without their federated identity would be
 * an orphan account that nobody can ever log into, and would then block the retry by occupying the
 * email address.
 *
 * <p>The concurrent-first-login retry is NOT here - it is in {@link FederatedLoginService}, because
 * recovering from a constraint violation requires a NEW transaction and self-invocation would not
 * be proxied.
 */
@Service
public class FederatedIdentityResolver {

  private static final Logger log = LoggerFactory.getLogger(FederatedIdentityResolver.class);

  private final FederatedIdentityRepository identities;
  private final UserAccountRepository users;
  private final RoleRepository roles;
  private final UserPrincipalService principals;
  private final TimeProvider timeProvider;
  private final AuthMetrics metrics;

  public FederatedIdentityResolver(
      FederatedIdentityRepository identities,
      UserAccountRepository users,
      RoleRepository roles,
      UserPrincipalService principals,
      TimeProvider timeProvider,
      AuthMetrics metrics) {
    this.identities = identities;
    this.users = users;
    this.roles = roles;
    this.principals = principals;
    this.timeProvider = timeProvider;
    this.metrics = metrics;
  }

  /**
   * Resolves an IdP assertion to a local principal, linking or provisioning as required.
   *
   * @throws AuthException when the login must be refused
   */
  @Transactional
  public AuthenticatedPrincipal resolve(OidcIdentity identity) {
    Optional<FederatedIdentity> existing =
        identities.findByProviderAndProviderUserId(identity.provider(), identity.subject());

    if (existing.isPresent()) {
      return login(existing.get(), identity);
    }
    if (!identity.emailVerified()) {
      metrics.oauth2Rejected("email_unverified");
      throw new AuthException(
          AuthErrorCode.AUTH_OIDC_EMAIL_UNVERIFIED,
          "Provider "
              + identity.provider()
              + " asserted email_verified=false for subject "
              + identity.subject());
    }
    if (identity.email() == null || identity.email().isBlank()) {
      // A verified-but-absent email cannot be matched or provisioned against.
      metrics.oauth2Rejected("no_email");
      throw new AuthException(
          AuthErrorCode.AUTH_OIDC_EMAIL_UNVERIFIED,
          "Provider " + identity.provider() + " returned no email claim");
    }

    Optional<UserAccount> local = users.findWithRolesByEmail(identity.email());
    return local.isPresent() ? link(local.get(), identity) : provision(identity);
  }

  /** Returning federated user. */
  private AuthenticatedPrincipal login(FederatedIdentity link, OidcIdentity identity) {
    UserAccount user =
        users
            .findWithRolesById(link.getUserId())
            .orElseThrow(
                () ->
                    // Impossible while the FK holds; if it ever happens the link
                    // is corrupt and must not authenticate anybody.
                    new AuthException(
                        AuthErrorCode.AUTH_OIDC_LOGIN_FAILED,
                        "Federated identity " + link.getId() + " points at a missing user"));

    requireAuthenticable(user);
    link.recordLogin(identity.email(), timeProvider.now());
    identities.save(link);
    return principals.principalFor(user, AuthMethod.GOOGLE);
  }

  /** Attaches a verified IdP account to an existing local user. */
  private AuthenticatedPrincipal link(UserAccount user, OidcIdentity identity) {
    requireAuthenticable(user);

    identities.save(
        FederatedIdentity.link(
            user.getId(),
            identity.provider(),
            identity.subject(),
            identity.email(),
            // Always true at this point - the false case threw above. Recorded
            // anyway so the audit trail proves it rather than implying it.
            identity.emailVerified(),
            timeProvider.now()));

    // The IdP has just proven the address that this local account was already
    // using, so a password-registered account becomes verified here.
    user.markEmailVerified();
    users.save(user);

    metrics.oauth2Link();
    log.info(
        "Linked provider={} to existing userId={} (email verified by IdP)",
        identity.provider(),
        user.getId());
    return principals.principalFor(user, AuthMethod.GOOGLE);
  }

  /** Creates a brand-new, social-only account. */
  private AuthenticatedPrincipal provision(OidcIdentity identity) {
    RoleEntity viewer =
        roles
            .findByName(Roles.ROLE_VIEWER)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Role " + Roles.ROLE_VIEWER + " is missing; check Flyway migrations"));

    UserAccount user = UserAccount.create(identity.email(), identity.displayName(), true);
    user.grant(viewer);
    // saveAndFlush, not save: the federated-identity insert below must hit the
    // UNIQUE constraint in THIS statement order, so that a concurrent duplicate
    // provisioning attempt fails here and can be retried as a login.
    users.saveAndFlush(user);

    identities.saveAndFlush(
        FederatedIdentity.link(
            user.getId(),
            identity.provider(),
            identity.subject(),
            identity.email(),
            true,
            timeProvider.now()));

    metrics.oauth2Provision();
    // NOTE: no user_credentials row. The account is social-only, so a password
    // login attempt against it fails as "unknown user" - deliberately, see
    // DatabaseUserDetailsService.
    log.info(
        "Provisioned userId={} from provider={} with role {}",
        user.getId(),
        identity.provider(),
        Roles.ROLE_VIEWER);
    return principals.principalFor(user, AuthMethod.GOOGLE);
  }

  private void requireAuthenticable(UserAccount user) {
    if (!user.canAuthenticate()) {
      metrics.oauth2Rejected(
          "account_" + user.getStatus().name().toLowerCase(java.util.Locale.ROOT));
      throw new AuthException(
          AuthErrorCode.AUTH_ACCOUNT_DISABLED, "User " + user.getId() + " is " + user.getStatus());
    }
  }
}
