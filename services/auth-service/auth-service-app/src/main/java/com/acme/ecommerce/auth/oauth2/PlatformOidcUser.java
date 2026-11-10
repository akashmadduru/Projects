package com.acme.ecommerce.auth.oauth2;

import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * An {@link OidcUser} that also carries the LOCAL principal resolved for it.
 *
 * <p>Without this, the success handler would have to redo the whole {@code sub ->
 * federated_identity -> user -> roles} lookup that {@link FederatedIdentityResolver} just did, and
 * the two could disagree.
 *
 * <p>{@link #getName()} returns the LOCAL user id rather than the IdP's {@code sub}, so that
 * anything keyed on the principal name (the authorized-client store, logs, metrics) uses the same
 * identifier as the rest of the platform.
 *
 * <p>{@link #getAuthorities()} is rebuilt from the local roles, NOT from the IdP's scopes. Google
 * has no opinion about who is an administrator here, and letting a scope influence authority would
 * make the IdP a privilege-granting authority for this platform.
 */
public final class PlatformOidcUser implements OidcUser {

  private final transient OidcUser delegate;
  private final transient AuthenticatedPrincipal principal;
  private final transient Collection<GrantedAuthority> authorities;

  PlatformOidcUser(OidcUser delegate, AuthenticatedPrincipal principal) {
    this.delegate = delegate;
    this.principal = principal;
    this.authorities =
        principal.roles().stream()
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
  }

  /** The resolved local principal, ready to hand to the token service. */
  public AuthenticatedPrincipal principal() {
    return principal;
  }

  @Override
  public Map<String, Object> getClaims() {
    return delegate.getClaims();
  }

  @Override
  public OidcUserInfo getUserInfo() {
    return delegate.getUserInfo();
  }

  @Override
  public OidcIdToken getIdToken() {
    return delegate.getIdToken();
  }

  @Override
  public Map<String, Object> getAttributes() {
    return delegate.getAttributes();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return authorities;
  }

  @Override
  public String getName() {
    return principal.userId().toString();
  }

  /**
   * Never renders the delegate. The underlying {@code OidcUser} holds the raw {@code id_token}, and
   * an accidental {@code log.debug("{}", principal)} would write a live Google credential to disk.
   */
  @Override
  public String toString() {
    return "PlatformOidcUser[userId=" + principal.userId() + ", roles=" + principal.roles() + "]";
  }
}
