package com.acme.ecommerce.auth.metrics;

import com.acme.ecommerce.auth.api.dto.AuthMethod;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The service's security-relevant counters, in one place.
 *
 * <p>Centralised rather than scattered {@code meterRegistry.counter(...)} calls so that metric
 * names and tag keys cannot drift - a dashboard breaks silently when one call site spells the tag
 * {@code result} and another spells it {@code outcome}.
 *
 * <p>CARDINALITY: every tag value here comes from a closed enum or a fixed string. Nothing derived
 * from user input (email, IP, user id) is ever a tag - that is how a metrics backend gets a million
 * time series and falls over.
 *
 * <p>THE ONE TO ALERT ON IS {@link #refreshReuseDetected()}. In a healthy system it is flat zero:
 * legitimate client races are absorbed by the grace window and counted as {@link #refreshRace()}
 * instead. Any non-zero rate means a refresh token was replayed outside that window, which means a
 * token left the browser it was issued to.
 */
@Component
public class AuthMetrics {

  private static final String TAG_METHOD = "method";
  private static final String TAG_OUTCOME = "outcome";

  private final MeterRegistry registry;

  public AuthMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** A login attempt finished. {@code outcome} is success / bad_credentials / locked / disabled. */
  public void loginAttempt(AuthMethod method, String outcome) {
    counter("auth.login.attempt", TAG_METHOD, method.claimValue(), TAG_OUTCOME, outcome);
  }

  /** A token pair was minted. Incremented inside the convergence point, so it counts BOTH paths. */
  public void tokenIssued(AuthMethod method) {
    counter("auth.token.issued", TAG_METHOD, method.claimValue());
  }

  /** A refresh token was successfully exchanged for a child. */
  public void refreshRotated() {
    counter("auth.refresh.rotated");
  }

  /**
   * A refresh token was replayed outside the grace window and its whole family was revoked.
   *
   * <p>ALERT ON THIS. See the class javadoc.
   */
  public void refreshReuseDetected() {
    counter("auth.refresh.reuse_detected");
  }

  /**
   * Two requests raced to rotate the same token. Expected at a low rate from multi-tab clients; a
   * sustained spike usually means a client is retrying aggressively, not that anything is wrong.
   */
  public void refreshRace() {
    counter("auth.refresh.race");
  }

  /** A refresh was rejected as unknown or expired. */
  public void refreshInvalid() {
    counter("auth.refresh.invalid");
  }

  /** A brand-new user was created from a Google login. */
  public void oauth2Provision() {
    counter("auth.oauth2.provision");
  }

  /** A Google identity was attached to an existing local user. */
  public void oauth2Link() {
    counter("auth.oauth2.link");
  }

  /** A Google login was refused. {@code reason} is a fixed, low-cardinality string. */
  public void oauth2Rejected(String reason) {
    counter("auth.oauth2.rejected", "reason", reason);
  }

  /** The per-IP throttle rejected a request. */
  public void rateLimited(String endpoint) {
    counter("auth.rate_limit.rejected", "endpoint", endpoint);
  }

  private void counter(String name, String... tags) {
    Counter.builder(name).tags(tags).register(registry).increment();
  }
}
