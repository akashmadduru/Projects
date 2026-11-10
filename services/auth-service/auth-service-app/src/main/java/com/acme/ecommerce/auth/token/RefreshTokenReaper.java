package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes refresh-token rows that can no longer affect any decision.
 *
 * <p>WHY THIS IS NEEDED. Every login creates a row and every refresh creates another. A single
 * active user generates roughly one row every ten minutes of use, forever, and none of them are
 * ever updated again after they are revoked. Left alone the table grows without bound, which slowly
 * degrades the two partial indexes the hot path depends on and eventually makes the nightly backup
 * the largest thing in the system.
 *
 * <p>ONLY TERMINAL STATES ARE DELETED - revoked or expired, and only after the retention window. A
 * live token is never touched regardless of age, so this job cannot log anybody out. The retention
 * window exists so that a reuse-detection incident can still be investigated afterwards: the chain
 * of parent ids is the evidence.
 *
 * <p>MULTI-REPLICA NOTE: with several instances this runs on each of them. That is harmless (the
 * DELETE is idempotent and the losers simply delete nothing) but wasteful. If the table ever grows
 * enough for the sweep to be expensive, this is the job to move behind a ZooKeeper lock or a
 * ShedLock-style leader election - deliberately not done now, because coordinating a cheap
 * idempotent DELETE is more machinery than the problem deserves.
 */
@Component
@ConditionalOnProperty(
    name = "auth.housekeeping.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RefreshTokenReaper {

  private static final Logger log = LoggerFactory.getLogger(RefreshTokenReaper.class);

  private final RefreshTokenStore store;
  private final TimeProvider timeProvider;
  private final AuthProperties.Housekeeping config;

  RefreshTokenReaper(
      RefreshTokenStore store, TimeProvider timeProvider, AuthProperties properties) {
    this.store = store;
    this.timeProvider = timeProvider;
    this.config = properties.getHousekeeping();
  }

  /**
   * Sweeps dead rows.
   *
   * <p>{@code fixedDelayString} rather than {@code fixedRateString}: with a fixed rate, a sweep
   * that runs long would immediately be followed by the next one, and two overlapping DELETEs over
   * the same range are pure lock contention.
   */
  @Scheduled(
      initialDelayString = "${auth.housekeeping.interval:1h}",
      fixedDelayString = "${auth.housekeeping.interval:1h}")
  public void sweep() {
    Instant cutoff = timeProvider.now().minus(config.retention());
    int deleted = store.deleteDeadTokensBefore(cutoff);
    if (deleted > 0) {
      log.info("Housekeeping removed {} dead refresh token(s) older than {}", deleted, cutoff);
    }
  }
}
