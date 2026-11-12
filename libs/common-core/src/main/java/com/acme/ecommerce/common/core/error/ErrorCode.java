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

  // --- Phase 6: sell for real -------------------------------------------------

  /** Not enough stock to reserve one or more order lines. */
  OUT_OF_STOCK("OUT_OF_STOCK", 409, "Out of stock"),

  /** The requested order status change is not allowed from the current status. */
  INVALID_STATUS_TRANSITION("INVALID_STATUS_TRANSITION", 409, "Invalid status transition"),

  /** The order can no longer be cancelled (already shipped, delivered or cancelled). */
  ORDER_NOT_CANCELLABLE("ORDER_NOT_CANCELLABLE", 409, "Order not cancellable"),

  /** The payment was declined or failed. */
  PAYMENT_FAILED("PAYMENT_FAILED", 402, "Payment failed"),

  /** No payment exists for the order. */
  PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", 404, "Payment not found"),

  /** The chosen payment method is not available for this order (e.g. COD over the limit). */
  PAYMENT_METHOD_UNAVAILABLE("PAYMENT_METHOD_UNAVAILABLE", 422, "Payment method unavailable"),

  /** Unknown or inactive coupon code. */
  COUPON_INVALID("COUPON_INVALID", 422, "Coupon invalid"),

  /** Coupon is outside its validity window. */
  COUPON_EXPIRED("COUPON_EXPIRED", 422, "Coupon expired"),

  /** Coupon conditions not met (minimum order, currency, first order only). */
  COUPON_NOT_APPLICABLE("COUPON_NOT_APPLICABLE", 422, "Coupon not applicable"),

  /** Coupon total or per-user usage limit reached. */
  COUPON_USAGE_LIMIT("COUPON_USAGE_LIMIT", 409, "Coupon usage limit reached"),

  /** Gift card code is unknown, already redeemed, expired or cancelled. */
  GIFT_CARD_INVALID("GIFT_CARD_INVALID", 422, "Gift card invalid"),

  /** Wallet or coin balance is too low for the requested debit. */
  INSUFFICIENT_BALANCE("INSUFFICIENT_BALANCE", 409, "Insufficient balance"),

  /** A deal ran out of claimable units. */
  DEAL_SOLD_OUT("DEAL_SOLD_OUT", 409, "Deal sold out"),

  /** The order or items are not eligible for return. */
  RETURN_NOT_ALLOWED("RETURN_NOT_ALLOWED", 409, "Return not allowed"),

  /** The return window for the order has passed. */
  RETURN_WINDOW_EXPIRED("RETURN_WINDOW_EXPIRED", 409, "Return window expired"),

  /** Pincode is malformed or not serviceable. */
  INVALID_PINCODE("INVALID_PINCODE", 400, "Invalid pincode"),

  /** Missing or wrong internal service token on an /internal/** endpoint. */
  INTERNAL_AUTH_FAILED("INTERNAL_AUTH_FAILED", 401, "Internal authentication failed"),

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
