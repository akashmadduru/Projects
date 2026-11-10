package com.acme.ecommerce.auth.ratelimit;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.error.ProblemResponseWriter;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.auth.web.RequestDeviceContext;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP throttle on the unauthenticated endpoints.
 *
 * <p>WHAT THIS IS AND IS NOT. It is a nuisance filter: it stops a single source hammering {@code
 * /login} from one machine, and it keeps a broken client from melting the database with refresh
 * retries. It is NOT the brute-force control - it is in-memory, so it is per-replica (three
 * replicas means three times the configured allowance) and it is per-source-address (a botnet or a
 * rotating proxy pool ignores it entirely). The real control is the per-account lockout in {@code
 * user_credentials}, which lives in the shared database and is attached to the account under attack
 * rather than to something the attacker chooses. A distributed (Redis-backed) limiter is a deferred
 * decision, not a forgotten one.
 *
 * <p>BOUNDED MAP. The IP -> bucket map is cleared when it exceeds {@code
 * auth.rate-limit.max-tracked-clients}. Without that bound, an attacker sending requests from
 * spoofed or rotating addresses grows it until the heap is gone - turning a rate limiter into a
 * denial-of-service amplifier. Clearing wholesale rather than evicting by age is a deliberate
 * simplification: it costs one free window to whoever was mid-limit, which is far cheaper than
 * carrying an eviction policy (and a scheduler) for a soft control.
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

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  private final AuthProperties.RateLimit config;
  private final RequestDeviceContext deviceContexts;
  private final ProblemResponseWriter problems;
  private final AuthMetrics metrics;

  public RateLimitFilter(
      AuthProperties properties,
      RequestDeviceContext deviceContexts,
      ProblemResponseWriter problems,
      AuthMetrics metrics) {
    this.config = properties.getRateLimit();
    this.deviceContexts = deviceContexts;
    this.problems = problems;
    this.metrics = metrics;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!config.isEnabled()) {
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
    String key = path + "|" + deviceContexts.clientIp(request);

    if (buckets.size() > config.getMaxTrackedClients()) {
      log.warn(
          "Rate-limit bucket map exceeded {} entries; clearing. "
              + "This usually means a source-address flood.",
          config.getMaxTrackedClients());
      buckets.clear();
    }

    Bucket bucket = buckets.computeIfAbsent(key, ignored -> newBucket(capacity, config.getWindow()));
    if (bucket.tryConsume(1)) {
      chain.doFilter(request, response);
      return;
    }

    metrics.rateLimited(path);
    log.info("Rate limit exceeded for {} (per-instance, soft control)", path);
    problems.write(request, response, AuthErrorCode.AUTH_RATE_LIMITED);
  }

  /** 0 means "not throttled". */
  private int capacityFor(String path) {
    if (LOGIN_PATH.equals(path) || REGISTER_PATH.equals(path)) {
      return config.getLoginCapacity();
    }
    if (REFRESH_PATH.equals(path)) {
      return config.getRefreshCapacity();
    }
    return 0;
  }

  private static Bucket newBucket(int capacity, Duration window) {
    return Bucket.builder()
        .addLimit(
            // Greedy refill: tokens trickle back continuously rather than all at
            // once at the end of the window. Interval refill produces a stampede
            // the instant the window rolls over, which is exactly the traffic
            // shape a limiter is supposed to prevent.
            Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
        .build();
  }
}
