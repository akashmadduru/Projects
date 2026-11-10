/**
 * Token issuance and the refresh-rotation state machine.
 *
 * <p>THE ACCESS-CONTROL BOUNDARY OF THIS SERVICE. {@code RefreshToken}, {@code
 * RefreshTokenRepository}, {@code RefreshTokenSecret}, {@code AccessTokenIssuer} and {@code
 * RefreshTokenStore} are all package-private, and {@code JwtKeyProvider}'s signing-key accessor is
 * too. The only public surface is:
 *
 * <ul>
 *   <li>{@code TokenService#issueTokenPair} - the one place a token pair is ever minted;
 *   <li>{@code RefreshTokenService} - rotation, logout and bulk revocation;
 *   <li>{@code JwtKeyProvider#publicJwkSet} - public key material for the JWKS endpoint.
 * </ul>
 *
 * <p>That is a compiler-enforced invariant, not a convention: no controller, no OAuth2 handler and
 * no future feature can construct a JWT or write a refresh-token row, because the types needed to
 * do so are not visible outside this package.
 */
package com.acme.ecommerce.auth.token;
