package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.common.web.job.JobLock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes one-time tokens (password reset, email/phone verification, OTP login, delete codes) that
 * expired before the housekeeping retention window (TD-P6-19). Expired codes can never be used, so
 * nothing a user can do is affected; the window keeps recent ones for investigating abuse. Same
 * schedule, switch and cluster lock as {@code RefreshTokenReaper}.
 */
@Component
@ConditionalOnProperty(
    name = "auth.housekeeping.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OneTimeTokenReaper {

  private static final Logger log = LoggerFactory.getLogger(OneTimeTokenReaper.class);

  private final OneTimeTokenService tokens;
  private final TimeProvider timeProvider;
  private final AuthProperties.Housekeeping config;
  private final JobLock jobLock;

  OneTimeTokenReaper(
      OneTimeTokenService tokens,
      TimeProvider timeProvider,
      AuthProperties properties,
      JobLock jobLock) {
    this.tokens = tokens;
    this.timeProvider = timeProvider;
    this.config = properties.housekeeping();
    this.jobLock = jobLock;
  }

  @Scheduled(
      initialDelayString = "${auth.housekeeping.interval:1h}",
      fixedDelayString = "${auth.housekeeping.interval:1h}")
  public void sweep() {
    jobLock.runExclusive("auth-one-time-token-reaper", this::sweepLocked);
  }

  void sweepLocked() {
    Instant cutoff = timeProvider.now().minus(config.retention());
    int deleted = tokens.purgeExpired(cutoff);
    if (deleted > 0) {
      log.info("Housekeeping removed {} expired one-time token(s) older than {}", deleted, cutoff);
    }
  }
}
