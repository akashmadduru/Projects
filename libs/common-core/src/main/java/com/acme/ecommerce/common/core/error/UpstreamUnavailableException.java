package com.acme.ecommerce.common.core.error;

/**
 * Thrown when a downstream/collaborating service could not be reached, returned an unusable
 * response, or timed out.
 *
 * <p>Kept distinct from {@link ErrorCode#INTERNAL_ERROR} so that dashboards and alerting can
 * separate "we are broken" from "our dependency is broken" - they have different owners and
 * different runbooks.
 */
public class UpstreamUnavailableException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public UpstreamUnavailableException(String upstream, Throwable cause) {
    super(
        ErrorCode.UPSTREAM_UNAVAILABLE,
        "Upstream service unavailable: " + upstream,
        detailsWith("upstream", upstream),
        cause);
  }
}
