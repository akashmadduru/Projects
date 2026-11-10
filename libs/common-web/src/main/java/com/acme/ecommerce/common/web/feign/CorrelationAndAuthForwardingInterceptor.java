package com.acme.ecommerce.common.web.feign;

import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Propagates the caller's identity and correlation id onto outbound Feign calls.
 *
 * <p>Without this, a service-to-service hop arrives at the callee anonymous (so its resource-server
 * config rejects it) and untraceable (so its logs cannot be joined to the originating request).
 *
 * <p>TOKEN HANDLING: the inbound {@code Authorization} header is forwarded verbatim - "token
 * relay". The downstream service therefore sees the ORIGINAL end user, and its own authorization
 * rules apply to that user rather than to a broad service account. That is the desired behaviour
 * for user-initiated call chains, and it means a downstream service can never be used to escalate
 * beyond what the user could already do.
 *
 * <p>LIMITATION - this only works on a thread that has request context bound. For calls made from
 * {@code @Scheduled} jobs, message consumers or an executor, there is no inbound request and hence
 * no token to relay; those paths need a client-credentials token supplied by their own interceptor.
 * The correlation id is still propagated in that case, since it comes from the (inheritable)
 * ThreadLocal rather than the request.
 *
 * <p>An existing header on the template is never overwritten, so a call site that deliberately sets
 * its own {@code Authorization} wins.
 */
public class CorrelationAndAuthForwardingInterceptor implements RequestInterceptor {

  private static final String AUTHORIZATION_HEADER = "Authorization";

  @Override
  public void apply(RequestTemplate template) {
    forwardCorrelationId(template);
    forwardAuthorization(template);
  }

  private void forwardCorrelationId(RequestTemplate template) {
    if (hasHeader(template, CorrelationIdHolder.HEADER_NAME)) {
      return;
    }
    // getOrGenerate: even a call with no inbound request gets a traceable id.
    template.header(CorrelationIdHolder.HEADER_NAME, CorrelationIdHolder.getOrGenerate());
  }

  private void forwardAuthorization(RequestTemplate template) {
    if (hasHeader(template, AUTHORIZATION_HEADER)) {
      return;
    }
    currentRequestAttributes()
        .map(attributes -> attributes.getRequest().getHeader(AUTHORIZATION_HEADER))
        .filter(header -> !header.isBlank())
        .ifPresent(header -> template.header(AUTHORIZATION_HEADER, header));
  }

  private static java.util.Optional<ServletRequestAttributes> currentRequestAttributes() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    return attributes instanceof ServletRequestAttributes servletAttributes
        ? java.util.Optional.of(servletAttributes)
        : java.util.Optional.empty();
  }

  private static boolean hasHeader(RequestTemplate template, String name) {
    java.util.Collection<String> values = template.headers().get(name);
    return values != null && !values.isEmpty();
  }
}
