package com.acme.ecommerce.common.core.error;

/**
 * Stable, machine-readable catalogue of failure modes exposed across service boundaries.
 *
 * <p>These constants are part of the platform's public API contract: clients (including the admin
 * SPA) branch on {@link #code()}, so an existing constant's {@code code} string must never be
 * renamed once released. Add new constants instead.
 *
 * <p>The {@link #type()} URI is what lands in the RFC 9457 {@code type} member. It is a stable
 * identifier, not necessarily a dereferenceable URL.
 */
public enum ErrorCode {

  /** Request body or parameters failed bean validation / syntactic checks. */
  VALIDATION_FAILED("VALIDATION_FAILED", 400, "Validation failed"),

  /** Request was well-formed but semantically rejected by a domain rule. */
  DOMAIN_RULE_VIOLATION("DOMAIN_RULE_VIOLATION", 422, "Domain rule violation"),

  /** No credentials, or credentials that could not be verified. */
  UNAUTHENTICATED("UNAUTHENTICATED", 401, "Unauthenticated"),

  /** Caller is known but lacks the authority for this operation. */
  FORBIDDEN("FORBIDDEN", 403, "Forbidden"),

  /** The addressed resource does not exist (or is not visible to this caller). */
  RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", 404, "Resource not found"),

  /** The write conflicts with current state (unique constraint, optimistic lock, duplicate). */
  RESOURCE_CONFLICT("RESOURCE_CONFLICT", 409, "Resource conflict"),

  /** A downstream service was unreachable, errored, or timed out. */
  UPSTREAM_UNAVAILABLE("UPSTREAM_UNAVAILABLE", 503, "Upstream service unavailable"),

  /** Checkout attempted with an empty cart. */
  CART_EMPTY("CART_EMPTY", 409, "Cart is empty"),

  /** A product in the cart is inactive or no longer exists. */
  PRODUCT_UNAVAILABLE("PRODUCT_UNAVAILABLE", 409, "Product unavailable"),

  /** Cart lines (or an added line) use more than one currency. */
  CURRENCY_MISMATCH("CURRENCY_MISMATCH", 409, "Currency mismatch"),

  /** The cart already holds the maximum number of distinct lines. */
  CART_LIMIT("CART_LIMIT", 409, "Cart limit reached"),

  /** The user already holds the maximum number of wishlist items. */
  WISHLIST_LIMIT("WISHLIST_LIMIT", 409, "Wishlist limit reached"),

  /** The addressed shipping address does not exist for this user. */
  ADDRESS_NOT_FOUND("ADDRESS_NOT_FOUND", 404, "Address not found"),

  /** The user already holds the maximum number of saved addresses. */
  ADDRESS_LIMIT("ADDRESS_LIMIT", 409, "Address limit reached"),

  /** Only customers who bought a product may review it. */
  REVIEW_NOT_ELIGIBLE("REVIEW_NOT_ELIGIBLE", 403, "Review not eligible"),

  /** The user already reviewed this product. */
  REVIEW_ALREADY_EXISTS("REVIEW_ALREADY_EXISTS", 409, "Review already exists"),

  /** A user may not vote on their own review. */
  CANNOT_VOTE_OWN_REVIEW("CANNOT_VOTE_OWN_REVIEW", 409, "Cannot vote on own review"),

  /** A collaborating service needed to complete the request is unavailable. */
  DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE", 503, "Dependency unavailable"),

  /** Anything unhandled. Deliberately opaque - details go to logs, never to the caller. */
  INTERNAL_ERROR("INTERNAL_ERROR", 500, "Internal server error");

  private static final String TYPE_PREFIX = "https://errors.acme-ecommerce.internal/";

  private final String code;
  private final int status;
  private final String title;

  ErrorCode(String code, int status, String title) {
    this.code = code;
    this.status = status;
    this.title = title;
  }

  /** Stable machine-readable code, surfaced as an extension member on the problem document. */
  public String code() {
    return code;
  }

  /** Default HTTP status for this failure mode. */
  public int status() {
    return status;
  }

  /** Short, human-readable summary; the RFC 9457 {@code title} member. */
  public String title() {
    return title;
  }

  /** Stable problem-type URI; the RFC 9457 {@code type} member. */
  public String type() {
    return TYPE_PREFIX + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }
}
