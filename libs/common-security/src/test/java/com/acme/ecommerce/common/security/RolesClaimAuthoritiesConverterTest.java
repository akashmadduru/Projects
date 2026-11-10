package com.acme.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RolesClaimAuthoritiesConverterTest {

  private final RolesClaimAuthoritiesConverter converter = new RolesClaimAuthoritiesConverter();

  @Test
  @DisplayName("maps a JSON array roles claim to ROLE_-prefixed authorities")
  void mapsArrayClaim() {
    Jwt jwt = jwtWithClaim("roles", List.of("ADMIN", "VIEWER"));

    assertThat(authorityNames(jwt)).containsExactlyInAnyOrder(Roles.ROLE_ADMIN, Roles.ROLE_VIEWER);
  }

  @Test
  @DisplayName("accepts a space-delimited string claim")
  void mapsSpaceDelimitedClaim() {
    Jwt jwt = jwtWithClaim("roles", "ADMIN CATALOG_MANAGER");

    assertThat(authorityNames(jwt))
        .containsExactlyInAnyOrder(Roles.ROLE_ADMIN, Roles.ROLE_CATALOG_MANAGER);
  }

  @Test
  @DisplayName("accepts a comma-delimited string claim")
  void mapsCommaDelimitedClaim() {
    Jwt jwt = jwtWithClaim("roles", "ADMIN,VIEWER");

    assertThat(authorityNames(jwt)).containsExactlyInAnyOrder(Roles.ROLE_ADMIN, Roles.ROLE_VIEWER);
  }

  @Test
  @DisplayName("does not double-prefix a claim that already carries ROLE_")
  void doesNotDoublePrefix() {
    Jwt jwt = jwtWithClaim("roles", List.of("ROLE_ADMIN"));

    assertThat(authorityNames(jwt)).containsExactly(Roles.ROLE_ADMIN);
  }

  @Test
  @DisplayName("normalises case so hasRole('ADMIN') matches a lower-case claim")
  void normalisesCase() {
    Jwt jwt = jwtWithClaim("roles", List.of("admin"));

    assertThat(authorityNames(jwt)).containsExactly(Roles.ROLE_ADMIN);
  }

  @Test
  @DisplayName("fails closed with no authorities when the claim is absent")
  void failsClosedWhenClaimAbsent() {
    Jwt jwt = jwtWithClaim("scope", "read");

    assertThat(converter.convert(jwt)).isEmpty();
  }

  @Test
  @DisplayName("fails closed when the claim has an unexpected shape")
  void failsClosedOnUnexpectedShape() {
    Jwt jwt = jwtWithClaim("roles", Map.of("unexpected", "shape"));

    assertThat(converter.convert(jwt)).isEmpty();
  }

  @Test
  @DisplayName("honours a custom claim name")
  void honoursCustomClaimName() {
    Jwt jwt = jwtWithClaim("authorities", List.of("VIEWER"));

    assertThat(new RolesClaimAuthoritiesConverter("authorities").convert(jwt))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly(Roles.ROLE_VIEWER);
  }

  private List<String> authorityNames(Jwt jwt) {
    return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
  }

  private static Jwt jwtWithClaim(String name, Object value) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject("user-1")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .claim(name, value)
        .build();
  }
}
