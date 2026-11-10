package com.acme.ecommerce.gateway.filter;

import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import java.util.List;
import java.util.Locale;

/**
 * The header namespace the edge owns.
 *
 * <p>Two rules, and the second one is the one that gets forgotten:
 *
 * <ol>
 *   <li>Downstream services may trust {@code X-User-*} / {@code X-Auth-*} / {@code X-Internal-*}
 *       headers <em>because</em> the gateway derives them from a validated token.
 *   <li>That trust is only justified if a client can never set them. {@link
 *       InboundHeaderSanitizationWebFilter} strips the entire namespace on the way in, before any
 *       other component in the process gets to look at the request.
 * </ol>
 *
 * <p>The prefix list is deliberately a <em>prefix</em> list rather than an exact-name list: a
 * future header called {@code X-User-Tenant} must be untrusted by default, not untrusted only once
 * someone remembers to add it here.
 */
public final class GatewayHeaders {

  /** Validated {@code sub} claim - the stable user id. */
  public static final String USER_ID = "X-User-Id";

  /**
   * Comma-separated role names, UNPREFIXED, exactly as they appear in the token's `roles` claim.
   */
  public static final String USER_ROLES = "X-User-Roles";

  /** Validated {@code email} claim. */
  public static final String USER_EMAIL = "X-User-Email";

  /** Validated {@code auth_method} claim: {@code password} or {@code google}. */
  public static final String AUTH_METHOD = "X-Auth-Method";

  /** Shared with every other service in the platform via common-core. */
  public static final String CORRELATION_ID = CorrelationIdHolder.HEADER_NAME;

  /**
   * Inbound header prefixes that are always stripped.
   *
   * <p>Lower-cased because HTTP header names are case-insensitive and a client that sends {@code
   * x-user-id} must be treated identically to one that sends {@code X-User-Id}.
   *
   * <p>Note {@code x-auth-} does NOT match {@code Authorization} - the bearer token is the one
   * client-supplied credential the gateway does want.
   */
  public static final List<String> UNTRUSTED_INBOUND_PREFIXES =
      List.of("x-user-", "x-auth-", "x-internal-");

  private GatewayHeaders() {
    throw new AssertionError("constants holder");
  }

  /** True if a client-supplied header with this name must be dropped. */
  public static boolean isUntrustedInbound(String headerName) {
    if (headerName == null) {
      return false;
    }
    String lower = headerName.toLowerCase(Locale.ROOT);
    return UNTRUSTED_INBOUND_PREFIXES.stream().anyMatch(lower::startsWith);
  }
}
