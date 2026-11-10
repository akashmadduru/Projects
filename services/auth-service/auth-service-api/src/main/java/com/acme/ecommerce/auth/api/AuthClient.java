package com.acme.ecommerce.auth.api;

import com.acme.ecommerce.auth.api.dto.UserResponse;
import com.acme.ecommerce.auth.api.dto.ValidateRequest;
import com.acme.ecommerce.auth.api.dto.ValidateResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Declarative client for the parts of auth-service another service may legitimately call.
 *
 * <p>Annotated with Spring MVC annotations rather than feign's own, because Spring Cloud
 * OpenFeign's default contract is the Spring MVC one. This interface is NOT annotated
 * {@code @FeignClient}: the consumer supplies that, so it controls the service id, the fallback and
 * the configuration class. A consumer wires it up as:
 *
 * <pre>{@code
 * @FeignClient(name = "auth-service", contextId = "authClient")
 * public interface MyAuthClient extends AuthClient {}
 * }</pre>
 *
 * <p>DELIBERATELY ABSENT: {@code login}, {@code register}, {@code refresh} and {@code logout}.
 * Those are browser-facing flows that depend on cookies and on the caller's own IP for rate
 * limiting; proxying them through another service would attribute every login attempt in the
 * platform to one source address and silently defeat the throttle.
 *
 * <p>PERFORMANCE WARNING: {@link #validate} is an out-of-process round trip. The gateway must NOT
 * use it - it validates tokens locally against {@code /.well-known/jwks.json}, which keeps
 * auth-service off the critical path of every request. This client exists for consumers that cannot
 * do local JWKS validation.
 *
 * <p>The caller's {@code Authorization} and {@code X-Correlation-Id} headers are relayed
 * automatically by {@code CorrelationAndAuthForwardingInterceptor} in common-web, so neither
 * appears in these signatures.
 */
public interface AuthClient {

  /** Base path shared by every auth endpoint. */
  String BASE_PATH = "/api/auth";

  /** Public JWKS document; the only endpoint outside {@link #BASE_PATH}. */
  String JWKS_PATH = "/.well-known/jwks.json";

  /**
   * Introspects a token.
   *
   * @param request may be {@code null}/empty to introspect the caller's own bearer token
   * @return the introspection result; {@code valid=false} carries no principal detail
   */
  @PostMapping(
      value = BASE_PATH + "/validate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ValidateResponse validate(@RequestBody ValidateRequest request);

  /** Returns the user identified by the caller's bearer token, with roles. */
  @GetMapping(value = BASE_PATH + "/me", produces = MediaType.APPLICATION_JSON_VALUE)
  UserResponse currentUser();
}
