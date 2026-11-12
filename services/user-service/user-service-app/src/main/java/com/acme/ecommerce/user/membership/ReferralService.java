package com.acme.ecommerce.user.membership;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReferralService {

  private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  public record ReferralView(String code, String link, long invited, long rewarded, long rewards) {}

  public record ByReferee(UUID referrerUserId, String code, boolean rewarded) {}

  private final JdbcClient jdbc;
  private final String storefront;
  private final int rewardCoins;

  public ReferralService(
      JdbcClient jdbc,
      @Value("${user.storefront-url:http://localhost:3000}") String storefront,
      @Value("${user.referral.reward-coins:100}") int rewardCoins) {
    this.jdbc = jdbc;
    this.storefront = storefront;
    this.rewardCoins = rewardCoins;
  }

  @Transactional
  public ReferralView view(UUID userId) {
    String code = codeFor(userId);
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

  @Transactional
  public void claim(UUID userId, String rawCode) {
    String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    UUID referrer =
        jdbc.sql("SELECT user_id FROM users.referral_codes WHERE code = :c")
            .param("c", code)
            .query(UUID.class)
            .optional()
            .orElseThrow(
                () -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Referral code not found"));
    if (referrer.equals(userId)) {
      throw new CodedException(ErrorCode.DOMAIN_RULE_VIOLATION, "You cannot use your own code");
    }
    try {
      jdbc.sql(
              "INSERT INTO users.referral_claims (referee_user_id, referrer_user_id, code) VALUES"
                  + " (:r, :p, :c)")
          .param("r", userId)
          .param("p", referrer)
          .param("c", code)
          .update();
    } catch (DuplicateKeyException e) {
      throw new CodedException(
          ErrorCode.RESOURCE_CONFLICT, "A referral code was already applied to this account");
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
