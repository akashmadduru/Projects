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
