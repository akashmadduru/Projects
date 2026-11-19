package com.acme.ecommerce.auth.ratelimit;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.error.ProblemResponseWriter;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.auth.web.RequestDeviceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP throttle on the unauthenticated endpoints.
 *
 * <p>WHAT THIS IS AND IS NOT. It is a nuisance filter: it stops a single source hammering {@code
 * /login} from one machine, and it keeps a broken client from melting the database with refresh
 * retries. It is NOT the brute-force control - it is per-source-address (a botnet or a rotating
 * proxy pool ignores it entirely). The real control is the per-account lockout in {@code
 * user_credentials}, which lives in the shared database and is attached to the account under attack
 * rather than to something the attacker chooses.
 *
 * <p>SHARED COUNTER. The count is a fixed-window Redis counter ({@link SharedWindowCounter}), so
 * every replica shares one allowance. If Redis is unreachable the counter falls back to a
 * per-instance, size-bounded map and never fails the request.
 *
 * <p>Runs BEFORE the security filter chain, so a flood is rejected before it costs a database
 * lookup or an Argon2 verification.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  /**
   * Runs ahead of Spring Security.
   *
   * <p>Spring Boot registers the security filter chain at {@code
   * SecurityProperties.DEFAULT_FILTER_ORDER} (-100), so anything that must reject a request before
   * authentication work happens has to sort lower than that. It still sorts well after common-web's
   * correlation-id filter ({@code HIGHEST_PRECEDENCE + 10}), so a throttled request is still logged
   * with a correlation id.
   */
  public static final int ORDER = -110;

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String REGISTER_PATH = "/api/auth/register";
  private static final String REFRESH_PATH = "/api/auth/refresh";

  /** Phase 6 unauthenticated or SMS/email-sending endpoints, throttled like login. */
  private static final java.util.Set<String> SENSITIVE_PATHS =
      java.util.Set.of(
          "/api/auth/password/forgot",
          "/api/auth/password/reset",
          "/api/auth/email/verify",
          "/api/auth/email/verify/resend",
          "/api/auth/otp/request",
          "/api/auth/otp/verify",
          "/api/auth/2fa/verify",
          "/api/auth/2fa/setup",
          "/api/auth/2fa/enable",
          "/api/auth/2fa/disable",
          "/api/auth/me/phone",
          "/api/auth/me/phone/verify");

  private final AuthProperties.RateLimit config;
  private final RequestDeviceContext deviceContexts;
  private final ProblemResponseWriter problems;
  private final AuthMetrics metrics;
  private final SharedWindowCounter counter;

  public RateLimitFilter(
      AuthProperties properties,
      RequestDeviceContext deviceContexts,
      ProblemResponseWriter problems,
      AuthMetrics metrics,
      SharedWindowCounter counter) {
    this.counter = counter;
    this.config = properties.rateLimit();
    this.deviceContexts = deviceContexts;
    this.problems = problems;
    this.metrics = metrics;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!config.enabled()) {
      return true;
    }
    return capacityFor(request.getRequestURI()) == 0;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String path = request.getRequestURI();
    int capacity = capacityFor(path);
    String key = "auth:rl:" + path + ":" + deviceContexts.clientIp(request);

    if (counter.increment(key, config.window()) <= capacity) {
      chain.doFilter(request, response);
      return;
    }

    metrics.rateLimited(path);
    log.info("Rate limit exceeded for {} (soft control)", path);
    problems.write(request, response, AuthErrorCode.AUTH_RATE_LIMITED);
  }

  /** 0 means "not throttled". */
  private int capacityFor(String path) {
    if (LOGIN_PATH.equals(path) || REGISTER_PATH.equals(path) || SENSITIVE_PATHS.contains(path)) {
      return config.loginCapacity();
    }
    if (REFRESH_PATH.equals(path)) {
      return config.refreshCapacity();
    }
    return 0;
  }
}
