package com.acme.ecommerce.user.membership;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.user.config.UserProperties;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReferralService {

  private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

  private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

  /** Most recent hashed IPs kept per user; bounds growth from a rotating client address. */
  static final int MAX_ACTIVITY_ROWS = 20;

  private static final SecureRandom RANDOM = new SecureRandom();

  public record ReferralView(String code, String link, long invited, long rewarded, long rewards) {}

  public record ByReferee(UUID referrerUserId, String code, boolean rewarded) {}

  private final JdbcClient jdbc;
  private final String storefront;
  private final int rewardCoins;
  private final IpFingerprint fingerprint;
  private final UserProperties properties;

  /** Cluster-wide guard so only one replica runs the retention job per tick. */
  @Autowired private JobLock jobLock;

  public ReferralService(
      JdbcClient jdbc,
      @Value("${user.storefront-url:http://localhost:3000}") String storefront,
      @Value("${user.referral.reward-coins:100}") int rewardCoins,
      IpFingerprint fingerprint,
      UserProperties properties) {
    this.jdbc = jdbc;
    this.storefront = storefront;
    this.rewardCoins = rewardCoins;
    this.fingerprint = fingerprint;
    this.properties = properties;
  }

  @Transactional
  public ReferralView view(UUID userId, InetAddress ip) {
    String code = codeFor(userId);
    if (ip != null && properties.getReferral().getIpLookback().toSeconds() > 0) {
      recordActivity(userId, fingerprint.hash(ip));
    }
    long invited =
        jdbc.sql("SELECT count(*) FROM users.referral_claims WHERE referrer_user_id = :u")
            .param("u", userId)
            .query(Long.class)
            .single();
    long rewarded =
        jdbc.sql(
                "SELECT count(*) FROM users.referral_claims WHERE referrer_user_id = :u AND"
                    + " rewarded_at IS NOT NULL")
            .param("u", userId)
            .query(Long.class)
            .single();
    return new ReferralView(
        code, storefront + "/register?ref=" + code, invited, rewarded, rewarded * rewardCoins);
  }

  /**
   * {@code ip} may be null when the caller's address is unknown; the IP checks are then skipped.
   */
  @Transactional
  public void claim(UUID userId, String rawCode, InetAddress ip) {
    String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    long minAgeSeconds = properties.getReferral().getMinCodeAge().toSeconds();
    CodeOwner owner =
        jdbc.sql(
                "SELECT user_id, created_at > now() - (:s * interval '1 second') AS too_new FROM"
                    + " users.referral_codes WHERE code = :c")
            .param("s", minAgeSeconds)
            .param("c", code)
            .query(
                (rs, n) ->
                    new CodeOwner(rs.getObject("user_id", UUID.class), rs.getBoolean("too_new")))
            .optional()
            .orElseThrow(
                () -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Referral code not found"));
    UUID referrer = owner.userId();
    if (referrer.equals(userId)) {
      throw new CodedException(ErrorCode.DOMAIN_RULE_VIOLATION, "You cannot use your own code");
    }
    if (claimExists(userId)) {
      throw new CodedException(
          ErrorCode.RESOURCE_CONFLICT, "A referral code was already applied to this account");
    }
    boolean ipCheckOn = properties.getReferral().getIpLookback().toSeconds() > 0;
    String ipHash = ip == null || !ipCheckOn ? null : fingerprint.hash(ip);
    refuseIfAbusive(referrer, owner.tooNew() && minAgeSeconds > 0, ipHash);
    try {
      jdbc.sql(
              "INSERT INTO users.referral_claims (referee_user_id, referrer_user_id, code,"
                  + " claim_ip_hash) VALUES (:r, :p, :c, :h)")
          .param("r", userId)
          .param("p", referrer)
          .param("c", code)
          .param("h", ipHash)
          .update();
    } catch (DuplicateKeyException e) {
      throw new CodedException(
          ErrorCode.RESOURCE_CONFLICT, "A referral code was already applied to this account");
    }
  }

  private record CodeOwner(UUID userId, boolean tooNew) {}

  /** Abuse checks, in order: code age, then network match. A throw rolls the claim back. */
  private void refuseIfAbusive(UUID referrer, boolean codeTooNew, String ipHash) {
    if (codeTooNew) {
      throw new CodedException(
          ErrorCode.REFERRAL_CODE_TOO_NEW, "This referral code cannot be used yet");
    }
    long lookback = properties.getReferral().getIpLookback().toSeconds();
    if (lookback > 0 && ipHash != null) {
      boolean sameNetwork =
          jdbc.sql(
                  "SELECT EXISTS (SELECT 1 FROM users.referral_ip_activity WHERE user_id = :p AND"
                      + " ip_hash = :h AND last_seen_at > now() - (:s * interval '1 second'))")
              .param("p", referrer)
              .param("h", ipHash)
              .param("s", lookback)
              .query(Boolean.class)
              .single();
      if (sameNetwork) {
        throw new CodedException(
            ErrorCode.REFERRAL_IP_MATCH, "This referral code cannot be used from this network");
      }
    }
  }

  private boolean claimExists(UUID referee) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM users.referral_claims WHERE referee_user_id = :r)")
        .param("r", referee)
        .query(Boolean.class)
        .single();
  }

  /**
   * Upserts the referrer's hashed IP (an existing row is only refreshed when over an hour old).
   * Only a newly inserted row can exceed the cap, so only then are all but the {@link
   * #MAX_ACTIVITY_ROWS} most recent rows for the user dropped; a refresh or a throttled view writes
   * nothing more.
   */
  private void recordActivity(UUID userId, String hash) {
    boolean inserted =
        jdbc.sql(
                "INSERT INTO users.referral_ip_activity (user_id, ip_hash) VALUES (:u, :h) ON"
                    + " CONFLICT (user_id, ip_hash) DO UPDATE SET last_seen_at = now() WHERE"
                    + " users.referral_ip_activity.last_seen_at < now() - interval '1 hour'"
                    + " RETURNING (xmax = 0)")
            .param("u", userId)
            .param("h", hash)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    if (!inserted) {
      return;
    }
    jdbc.sql(
            "DELETE FROM users.referral_ip_activity WHERE user_id = :u AND ip_hash NOT IN (SELECT"
                + " ip_hash FROM users.referral_ip_activity WHERE user_id = :u ORDER BY"
                + " last_seen_at DESC, ip_hash LIMIT :n)")
        .param("u", userId)
        .param("n", MAX_ACTIVITY_ROWS)
        .update();
  }

  /**
   * Daily: drops hashed IPs past the retention window; the claims themselves stay. A retention of
   * zero or less disables the purge.
   */
  @Scheduled(cron = "${user.referral.ip-cleanup-cron:0 40 3 * * *}")
  public void purgeExpiredIps() {
    jobLock.runExclusive("user-referral-ip-retention", this::purgeExpiredIpsLocked);
  }

  private void purgeExpiredIpsLocked() {
    long seconds = properties.getReferral().getIpRetention().toSeconds();
    if (seconds <= 0) {
      log.debug("Referral IP purge skipped: user.referral.ip-retention is zero or negative");
      return;
    }
    int activity =
        jdbc.sql(
                "DELETE FROM users.referral_ip_activity WHERE last_seen_at < now() - (:s *"
                    + " interval '1 second')")
            .param("s", seconds)
            .update();
    int claims =
        jdbc.sql(
                "UPDATE users.referral_claims SET claim_ip_hash = NULL WHERE claim_ip_hash IS NOT"
                    + " NULL AND claimed_at < now() - (:s * interval '1 second')")
            .param("s", seconds)
            .update();
    if (activity + claims > 0) {
      log.info(
          "Referral IP retention: removed {} activity row(s), cleared {} claim hash(es)",
          activity,
          claims);
    }
  }

  @Transactional(readOnly = true)
  public Optional<ByReferee> byReferee(UUID refereeId) {
    return jdbc.sql(
            "SELECT referrer_user_id, code, rewarded_at IS NOT NULL AS rewarded FROM"
                + " users.referral_claims WHERE referee_user_id = :r")
        .param("r", refereeId)
        .query(
            (rs, n) ->
                new ByReferee(
                    rs.getObject("referrer_user_id", UUID.class),
                    rs.getString("code"),
                    rs.getBoolean("rewarded")))
        .optional();
  }

  @Transactional
  public void markRewarded(UUID refereeId) {
    jdbc.sql(
            "UPDATE users.referral_claims SET rewarded_at = COALESCE(rewarded_at, now()) WHERE"
                + " referee_user_id = :r")
        .param("r", refereeId)
        .update();
  }

  private String codeFor(UUID userId) {
    Optional<String> existing =
        jdbc.sql("SELECT code FROM users.referral_codes WHERE user_id = :u")
            .param("u", userId)
            .query(String.class)
            .optional();
    if (existing.isPresent()) {
      return existing.get();
    }
    for (int attempt = 0; attempt < 10; attempt++) {
      StringBuilder sb = new StringBuilder(8);
      for (int i = 0; i < 8; i++) {
        sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      }
      int n =
          jdbc.sql(
                  "INSERT INTO users.referral_codes (user_id, code) VALUES (:u, :c) ON CONFLICT DO"
                      + " NOTHING")
              .param("u", userId)
              .param("c", sb.toString())
              .update();
      if (n > 0) {
        return sb.toString();
      }
      // conflict on user_id (concurrent request) or on code (collision): re-read, else retry
      Optional<String> raced =
          jdbc.sql("SELECT code FROM users.referral_codes WHERE user_id = :u")
              .param("u", userId)
              .query(String.class)
              .optional();
      if (raced.isPresent()) {
        return raced.get();
      }
    }
    throw new CodedException(ErrorCode.INTERNAL_ERROR, "Could not allocate a referral code");
  }
}
