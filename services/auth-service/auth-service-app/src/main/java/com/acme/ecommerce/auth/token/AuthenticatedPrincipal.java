package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.auth.api.dto.AuthMethod;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Everything token issuance needs to know about a caller, and nothing else.
 *
 * <p>THIS TYPE IS THE CONVERGENCE POINT'S INPUT CONTRACT. Password login and Google login both
 * reduce to one of these before {@link TokenService#issueTokenPair} is called, which is what
 * guarantees the two front doors produce structurally identical tokens. If a future authentication
 * method cannot be expressed as an {@code AuthenticatedPrincipal}, that is a signal to extend this
 * record - not to add a second issuance path.
 *
 * <p>Deliberately NOT a JPA entity and NOT Spring Security's {@code UserDetails}: it must be
 * constructible from an OIDC login, where there is no local credential to load, and it must never
 * carry a password hash anywhere near the token issuer.
 *
 * @param userId the subject; becomes the JWT {@code sub}
 * @param email becomes the {@code email} claim
 * @param roles UNPREFIXED role names ({@code "ADMIN"}), exactly as they appear in the {@code roles}
 *     claim. The database stores the {@code ROLE_}-prefixed authority form; the strip happens once,
 *     where the principal is built, so nothing downstream has to remember which convention it is
 *     holding.
 * @param authMethod how identity was proven for this session
 */
public record AuthenticatedPrincipal(
    UUID userId, String email, Set<String> roles, AuthMethod authMethod) {

  public AuthenticatedPrincipal {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(email, "email");
    Objects.requireNonNull(authMethod, "authMethod");
    // Defensive immutable copy. Set iteration order is unspecified, so the
    // token issuer sorts the names before writing the claim - a stable claim
    // ordering makes token diffs in tests and logs readable.
    roles = Set.copyOf(roles == null ? Set.of() : roles);
  }
}
