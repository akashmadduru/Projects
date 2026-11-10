package com.acme.ecommerce.common.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a JWT {@code roles} claim onto Spring {@link GrantedAuthority}s.
 *
 * <p>Spring's default converter reads OAuth2 {@code scope}/{@code scp} claims and emits {@code
 * SCOPE_*} authorities. This platform authorizes on roles issued by its own auth-service, so the
 * mapping has to be replaced rather than supplemented.
 *
 * <p>Accepts the claim as either a JSON array ({@code ["ADMIN","VIEWER"]}) or a
 * space/comma-delimited string, because both forms show up in the wild depending on the issuer.
 * Values are upper-cased and {@code ROLE_}-prefixed so that {@code hasRole('ADMIN')} matches.
 *
 * <p>A token with no roles claim yields an empty authority set - an authenticated principal with no
 * privileges. It is NOT an error: authorization rules then reject it, which is the correct
 * fail-closed behaviour.
 */
public class RolesClaimAuthoritiesConverter
    implements Converter<Jwt, Collection<GrantedAuthority>> {

  /** Claim name issued by auth-service. */
  public static final String DEFAULT_CLAIM_NAME = "roles";

  private final String claimName;

  public RolesClaimAuthoritiesConverter() {
    this(DEFAULT_CLAIM_NAME);
  }

  public RolesClaimAuthoritiesConverter(String claimName) {
    this.claimName = Objects.requireNonNull(claimName, "claimName");
  }

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    Object claim = jwt.getClaim(claimName);
    if (claim == null) {
      return Set.of();
    }

    Set<GrantedAuthority> authorities = new LinkedHashSet<>();
    for (String role : extractRoles(claim)) {
      String prefixed = Roles.prefixed(role.toUpperCase(java.util.Locale.ROOT));
      if (prefixed != null) {
        authorities.add(new SimpleGrantedAuthority(prefixed));
      }
    }
    return authorities;
  }

  private static List<String> extractRoles(Object claim) {
    if (claim instanceof Collection<?> collection) {
      return collection.stream()
          .filter(Objects::nonNull)
          .map(String::valueOf)
          .map(String::trim)
          .filter(value -> !value.isEmpty())
          .toList();
    }
    if (claim instanceof String text) {
      // Tolerate both "ADMIN VIEWER" and "ADMIN,VIEWER".
      return List.of(text.split("[,\\s]+")).stream()
          .map(String::trim)
          .filter(value -> !value.isEmpty())
          .toList();
    }
    // Unknown shape: fail closed with no authorities rather than guessing.
    return List.of();
  }
}
