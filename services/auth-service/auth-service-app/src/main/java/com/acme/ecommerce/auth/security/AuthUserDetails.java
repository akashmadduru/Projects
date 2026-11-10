package com.acme.ecommerce.auth.security;

import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserCredential;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security's view of a user during password authentication.
 *
 * <p>Carries the {@link #getUserId()} and {@link #getRoleClaimNames()} that token issuance needs,
 * so a successful login does NOT go back to the database to rebuild the principal. Without that,
 * every login would be two identical user loads.
 *
 * <p>The password hash is present because {@code DaoAuthenticationProvider} needs it, and is erased
 * by {@link #eraseCredentials()} the moment authentication completes.
 */
public final class AuthUserDetails implements UserDetails, CredentialsContainer {

  private static final long serialVersionUID = 1L;

  private final UUID userId;
  private final String email;
  private final boolean enabled;
  private final boolean accountNonLocked;
  private final Set<String> roleClaimNames;
  private final List<GrantedAuthority> authorities;

  private String passwordHash;

  private AuthUserDetails(
      UUID userId,
      String email,
      String passwordHash,
      boolean enabled,
      boolean accountNonLocked,
      Set<String> roleClaimNames,
      List<GrantedAuthority> authorities) {
    this.userId = userId;
    this.email = email;
    this.passwordHash = passwordHash;
    this.enabled = enabled;
    this.accountNonLocked = accountNonLocked;
    this.roleClaimNames = roleClaimNames;
    this.authorities = authorities;
  }

  /**
   * Builds the details from a user and their credential.
   *
   * @param now used to evaluate the transient lockout window, so the decision is testable
   */
  public static AuthUserDetails of(UserAccount user, UserCredential credential, Instant now) {
    List<GrantedAuthority> authorities =
        user.getRoles().stream()
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.getName()))
            .toList();
    Set<String> claimNames =
        user.getRoles().stream()
            .map(com.acme.ecommerce.auth.domain.RoleEntity::claimName)
            .collect(Collectors.toUnmodifiableSet());

    return new AuthUserDetails(
        user.getId(),
        user.getEmail(),
        credential.getPasswordHash(),
        user.canAuthenticate(),
        !credential.isLockedAt(now),
        claimNames,
        authorities);
  }

  public UUID getUserId() {
    return userId;
  }

  /** UNPREFIXED role names, ready for the JWT {@code roles} claim. */
  public Set<String> getRoleClaimNames() {
    return roleClaimNames;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return authorities;
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public boolean isAccountNonLocked() {
    return accountNonLocked;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void eraseCredentials() {
    this.passwordHash = null;
  }

  /** Never includes the hash. */
  @Override
  public String toString() {
    return "AuthUserDetails[userId=" + userId + ", email=" + email + ", enabled=" + enabled + "]";
  }
}
