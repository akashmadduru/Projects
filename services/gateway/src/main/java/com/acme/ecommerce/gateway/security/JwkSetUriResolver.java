package com.acme.ecommerce.gateway.security;

import java.net.URI;

/**
 * Decides where the JWKS document lives, at fetch time rather than at startup.
 *
 * <p>Late resolution is the whole point. auth-service's address comes from Eureka and changes with
 * every redeploy; a URL captured once during gateway startup would be pinned to whichever instance
 * happened to be registered at that moment - and in compose bring-up order, quite possibly to no
 * instance at all.
 */
@FunctionalInterface
public interface JwkSetUriResolver {

  /**
   * Resolves the current JWKS URL.
   *
   * @throws IllegalStateException if no instance can be resolved right now. The caller is inside
   *     Nimbus' outage-tolerant wrapper, so this degrades to "keep serving the cached key set"
   *     rather than to "reject every request".
   */
  URI resolve();
}
