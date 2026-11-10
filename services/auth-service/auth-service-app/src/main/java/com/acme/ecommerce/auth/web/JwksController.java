package com.acme.ecommerce.auth.web;

import com.acme.ecommerce.auth.api.AuthClient;
import com.acme.ecommerce.auth.token.JwtKeyProvider;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public keys every resource server in the platform verifies tokens with.
 *
 * <p>THE HIGHEST-CONSEQUENCE ENDPOINT IN THIS SERVICE. If a private key component ({@code d, p, q,
 * dp, dq, qi}) ever appeared in this document, anyone who fetched it could mint a valid token for
 * any user with any role, and no other control in the platform would notice - every service would
 * verify those tokens as genuine. {@link JwtKeyProvider#publicJwkSet()} builds the response with
 * {@code toPublicJWK()} on every entry, and there is an explicit test asserting the private members
 * are absent. Do not "simplify" this by serialising the JWK set directly.
 *
 * <p>PUBLIC AND UNAUTHENTICATED, correctly so: a public key is public, and requiring credentials
 * would create a circular dependency where a service needs a token to fetch the keys it needs to
 * validate tokens.
 *
 * <p>{@code Cache-Control: max-age=300} is a real capacity decision, not decoration. Every resource
 * server fetches this on cache miss; without it, auth-service takes a request from every instance
 * in the fleet on every token that carries an unfamiliar {@code kid}. Five minutes also bounds how
 * long a rotation takes to propagate, which is why the rotation procedure says "wait" between
 * adding a key and activating it.
 */
@RestController
public class JwksController {

  private final JwtKeyProvider keyProvider;

  public JwksController(JwtKeyProvider keyProvider) {
    this.keyProvider = keyProvider;
  }

  /** The JWKS document. Public key material only. */
  @GetMapping(value = AuthClient.JWKS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> jwks() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(300, TimeUnit.SECONDS).cachePublic())
        // `true` = public keys only. Passing false (or using the no-arg
        // toJSONObject) would serialise the private key components.
        .body(keyProvider.publicJwkSet().toJSONObject(true));
  }
}
