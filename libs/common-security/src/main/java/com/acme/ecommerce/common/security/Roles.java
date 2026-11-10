package com.acme.ecommerce.common.security;

/**
 * Canonical role names for the platform.
 *
 * <p>Spring's {@code hasRole('X')} implicitly prepends {@code ROLE_}, while {@code hasAuthority()}
 * and {@code SimpleGrantedAuthority} do not. Mixing the two conventions by hand is a classic source
 * of authorization rules that silently never match. Both spellings are therefore published here:
 * use {@code Roles.ADMIN} with {@code hasRole(...)}, and {@code Roles.ROLE_ADMIN} with {@code
 * hasAuthority(...)} or when constructing authorities.
 *
 * <p>The JWT {@code roles} claim carries the UNPREFIXED names ({@code "ADMIN"}); {@link
 * RolesClaimAuthoritiesConverter} adds the prefix when building authorities.
 */
public final class Roles {

  // --- unprefixed: the values that appear in the JWT `roles` claim ---
  /** Full administrative access, including user management. */
  public static final String ADMIN = "ADMIN";

  /** May create and modify catalog content, but not manage users. */
  public static final String CATALOG_MANAGER = "CATALOG_MANAGER";

  /** Read-only access. */
  public static final String VIEWER = "VIEWER";

  // --- prefixed: the GrantedAuthority form ---
  public static final String ROLE_PREFIX = "ROLE_";

  public static final String ROLE_ADMIN = ROLE_PREFIX + ADMIN;
  public static final String ROLE_CATALOG_MANAGER = ROLE_PREFIX + CATALOG_MANAGER;
  public static final String ROLE_VIEWER = ROLE_PREFIX + VIEWER;

  private Roles() {
    throw new AssertionError("constants holder");
  }

  /** Adds the {@code ROLE_} prefix unless it is already present. */
  public static String prefixed(String role) {
    if (role == null || role.isBlank()) {
      return null;
    }
    String trimmed = role.trim();
    return trimmed.startsWith(ROLE_PREFIX) ? trimmed : ROLE_PREFIX + trimmed;
  }
}
