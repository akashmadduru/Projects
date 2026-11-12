package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.api.dto.UserResponse;
import com.acme.ecommerce.auth.domain.RoleEntity;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import com.acme.ecommerce.auth.token.PrincipalLoader;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a stored {@link UserAccount} into the shapes the rest of the service needs: an {@link
 * AuthenticatedPrincipal} for token issuance and a {@link UserResponse} for the API.
 *
 * <p>Also the implementation of {@link PrincipalLoader}, which is how refresh rotation rebuilds a
 * principal WITHOUT the token package having to know that users, roles or repositories exist.
 *
 * <p>ROLES ARE READ FRESH ON EVERY REFRESH, never snapshotted onto the refresh-token row. That is
 * what bounds the blast radius of a revoked role to one access-token lifetime (10 minutes) instead
 * of one refresh-token lifetime (14 days). It costs one indexed join per refresh, which is a trade
 * worth making every time.
 */
@Service
public class UserPrincipalService implements PrincipalLoader {

  private final UserAccountRepository users;

  public UserPrincipalService(UserAccountRepository users) {
    this.users = users;
  }

  @Override
  @Transactional(readOnly = true)
  public AuthenticatedPrincipal load(UUID userId, AuthMethod authMethod) {
    UserAccount user =
        users
            .findWithRolesById(userId)
            .orElseThrow(
                () ->
                    // The user was deleted while a session was live. Fail closed
                    // and say nothing specific: the caller holds a token for an
                    // account that no longer exists, and there is nothing useful
                    // or safe to tell them beyond "re-authenticate".
                    new AuthException(
                        AuthErrorCode.AUTH_REFRESH_INVALID,
                        "No user for id " + userId + " referenced by a live session"));

    if (!user.canAuthenticate()) {
      throw new AuthException(
          AuthErrorCode.AUTH_ACCOUNT_DISABLED,
          "User " + userId + " is " + user.getStatus() + " and may not refresh");
    }
    return principalFor(user, authMethod);
  }

  /** Builds the token-issuance principal from an already-loaded user. */
  public AuthenticatedPrincipal principalFor(UserAccount user, AuthMethod authMethod) {
    return new AuthenticatedPrincipal(user.getId(), user.getEmail(), claimNames(user), authMethod);
  }

  /** Loads the API representation of a user, with roles, in one query. */
  @Transactional(readOnly = true)
  public UserResponse currentUser(UUID userId) {
    UserAccount user =
        users
            .findWithRolesById(userId)
            .orElseThrow(
                () ->
                    new AuthException(
                        AuthErrorCode.AUTH_INVALID_CREDENTIALS,
                        "Token subject " + userId + " does not resolve to a user"));
    return toResponse(user);
  }

  /** Maps a user (with roles already fetched) to the published response shape. */
  public UserResponse toResponse(UserAccount user) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.isEmailVerified(),
        user.getPhone(),
        user.isPhoneVerified(),
        user.getDisplayName(),
        user.getStatus().name(),
        claimNames(user).stream().sorted().toList(),
        user.getCreatedAt());
  }

  private static Set<String> claimNames(UserAccount user) {
    return user.getRoles().stream()
        .map(RoleEntity::claimName)
        .collect(Collectors.toUnmodifiableSet());
  }
}
