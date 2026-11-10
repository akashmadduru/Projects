package com.acme.ecommerce.common.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The platform's {@link Jwt} to {@link Authentication} converter.
 *
 * <p>Wires {@link RolesClaimAuthoritiesConverter} into Spring's {@link JwtAuthenticationConverter}
 * and sets the principal name to the {@code sub} claim, so {@code Authentication#getName()} returns
 * the stable user id rather than a display name that may change.
 *
 * <p>Every resource server in the platform must use this, otherwise services would disagree about
 * what a token means - the kind of drift that produces an endpoint that is accidentally open.
 */
public class PlatformJwtAuthenticationConverter
    implements org.springframework.core.convert.converter.Converter<
        Jwt, AbstractAuthenticationToken> {

  /** Claim carrying the stable subject identifier. */
  private static final String PRINCIPAL_CLAIM = "sub";

  private final JwtAuthenticationConverter delegate;

  public PlatformJwtAuthenticationConverter() {
    this(new RolesClaimAuthoritiesConverter());
  }

  public PlatformJwtAuthenticationConverter(RolesClaimAuthoritiesConverter authoritiesConverter) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter::convert);
    converter.setPrincipalClaimName(PRINCIPAL_CLAIM);
    this.delegate = converter;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    return delegate.convert(jwt);
  }

  /**
   * Convenience accessor for tests and for code that needs the concrete token type.
   *
   * @return the produced token, or null if conversion yielded nothing
   */
  public JwtAuthenticationToken convertToJwtToken(Jwt jwt) {
    AbstractAuthenticationToken token = convert(jwt);
    return token instanceof JwtAuthenticationToken jwtToken ? jwtToken : null;
  }
}
