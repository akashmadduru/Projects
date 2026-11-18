package com.acme.ecommerce.user.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.user.config.UserProperties;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** TD-P8-13: referral abuse checks, against a real Postgres. */
class ReferralAbuseIntegrationTest extends AbstractUserIntegrationTest {

  private static final String HEX64 = "^[0-9a-f]{64}$";

  @Autowired private JdbcClient jdbc;
  @Autowired private UserProperties properties;
  @Autowired private ReferralService referrals;
  @Autowired private IpFingerprint fingerprint;

  private static Optional<InetAddress> ip(String literal) throws Exception {
    return Optional.of(InetAddress.getByName(literal));
  }

  private String hashOf(String literal) throws Exception {
    return fingerprint.hash(InetAddress.getByName(literal));
  }

  private List<Map<String, Object>> activity(UUID user) {
    return jdbc.sql("SELECT * FROM users.referral_ip_activity WHERE user_id = :u")
        .param("u", user)
        .query()
        .listOfRows();
  }

  private String claimHash(UUID referee) {
    return jdbc.sql("SELECT claim_ip_hash FROM users.referral_claims WHERE referee_user_id = :u")
        .param("u", referee)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private int claimRows(UUID referee) {
    return jdbc.sql("SELECT count(*)::int FROM users.referral_claims WHERE referee_user_id = :u")
        .param("u", referee)
        .query(Integer.class)
        .single();
  }

  @Test
  @DisplayName("V5 creates the activity table, the claim hash column and the primary key")
  void schemaExists() {
    Integer pkColumns =
        jdbc.sql(
                "SELECT count(*)::int FROM information_schema.key_column_usage WHERE"
                    + " table_schema = 'users' AND table_name = 'referral_ip_activity' AND"
                    + " constraint_name = 'referral_ip_activity_pkey' AND column_name IN"
                    + " ('user_id','ip_hash')")
            .query(Integer.class)
            .single();
    assertThat(pkColumns).isEqualTo(2);
    Integer col =
        jdbc.sql(
                "SELECT count(*)::int FROM information_schema.columns WHERE table_schema = 'users'"
                    + " AND table_name = 'referral_claims' AND column_name = 'claim_ip_hash'")
            .query(Integer.class)
            .single();
    assertThat(col).isEqualTo(1);
  }

  @Test
  @DisplayName("the referral properties bind their defaults and the test salt")
  void propertiesBind() {
    UserProperties.Referral referral = properties.getReferral();
    assertThat(referral.getMinCodeAge()).isEqualTo(Duration.ofMinutes(10));
    assertThat(referral.getIpLookback()).isEqualTo(Duration.ofDays(30));
    assertThat(referral.getIpRetention()).isEqualTo(Duration.ofDays(30));
    assertThat(referral.getIpHashSalt()).isEqualTo("test-only-referral-salt");
    assertThat(referral.toString()).doesNotContain("test-only-referral-salt");
  }

  @Test
  @DisplayName("view records one hashed row; repeats within the hour do not advance it")
  void viewRecordsThrottled() throws Exception {
    UUID user = UUID.randomUUID();
    referrals.view(user, ip("203.0.113.9"));
    List<Map<String, Object>> rows = activity(user);
    assertThat(rows).hasSize(1);
    assertThat((String) rows.get(0).get("ip_hash")).matches(HEX64).isEqualTo(hashOf("203.0.113.9"));

    Object first = rows.get(0).get("last_seen_at");
    referrals.view(user, ip("203.0.113.9"));
    referrals.view(user, ip("203.0.113.9"));
    rows = activity(user);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("last_seen_at")).isEqualTo(first);
  }

  @Test
  @DisplayName("a stale row is refreshed; a second IP adds a second row; no IP adds none")
  void viewRefreshesAndDedupes() throws Exception {
    UUID user = UUID.randomUUID();
    referrals.view(user, ip("203.0.113.9"));
    jdbc.sql(
            "UPDATE users.referral_ip_activity SET last_seen_at = now() - interval '2 hours'"
                + " WHERE user_id = :u")
        .param("u", user)
        .update();
    referrals.view(user, ip("203.0.113.9"));
    Integer stale =
        jdbc.sql(
                "SELECT count(*)::int FROM users.referral_ip_activity WHERE user_id = :u AND"
                    + " last_seen_at > now() - interval '1 hour'")
            .param("u", user)
            .query(Integer.class)
            .single();
    assertThat(stale).isEqualTo(1);

    referrals.view(user, ip("198.51.100.4"));
    assertThat(activity(user)).hasSize(2);
    referrals.view(user, Optional.empty());
    assertThat(activity(user)).hasSize(2);
  }

  @Test
  @DisplayName("claim stores the hash only; no column holds the raw IP")
  void claimStoresHashOnly() throws Exception {
    UUID referrer = UUID.randomUUID();
    UUID referee = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    referrals.claim(referee, code, ip("198.51.100.4"));

    assertThat(claimHash(referee)).matches(HEX64).isEqualTo(hashOf("198.51.100.4"));
    for (String table : List.of("referral_ip_activity", "referral_claims")) {
      List<Map<String, Object>> rows =
          jdbc.sql("SELECT * FROM users." + table).query().listOfRows();
      assertThat(rows.toString()).doesNotContain("203.0.113.9").doesNotContain("198.51.100.4");
    }
    UUID noIp = UUID.randomUUID();
    referrals.claim(noIp, code, Optional.empty());
    assertThat(claimRows(noIp)).isEqualTo(1);
    assertThat(claimHash(noIp)).isNull();
  }

  @Test
  @DisplayName("claiming an unknown code is 404 and records no activity")
  void unknownCodeRecordsNothing() throws Exception {
    UUID user = UUID.randomUUID();
    assertThatThrownBy(() -> referrals.claim(user, "NOSUCHCD", ip("198.51.100.4")))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    assertThat(activity(user)).isEmpty();
    assertThat(claimRows(user)).isZero();
  }

  @Test
  @DisplayName("own code is 422 and a second claim is 409 (unchanged behaviour)")
  void existingRulesUnchanged() throws Exception {
    UUID referrer = UUID.randomUUID();
    UUID referee = UUID.randomUUID();
    String code = referrals.view(referrer, Optional.empty()).code();
    assertThatThrownBy(() -> referrals.claim(referrer, code, Optional.empty()))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DOMAIN_RULE_VIOLATION));
    referrals.claim(referee, code, Optional.empty());
    assertThatThrownBy(() -> referrals.claim(referee, code, Optional.empty()))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
  }

  @Test
  @DisplayName("retention job deletes old activity and nulls old claim hashes, keeping claims")
  void purgeRemovesOnlyExpired() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    referrals.view(referrer, ip("203.0.113.10"));
    jdbc.sql(
            "UPDATE users.referral_ip_activity SET last_seen_at = now() - interval '31 days'"
                + " WHERE user_id = :u AND ip_hash = :h")
        .param("u", referrer)
        .param("h", hashOf("203.0.113.9"))
        .update();
    UUID oldReferee = UUID.randomUUID();
    UUID newReferee = UUID.randomUUID();
    referrals.claim(oldReferee, code, ip("198.51.100.4"));
    referrals.claim(newReferee, code, ip("198.51.100.5"));
    jdbc.sql(
            "UPDATE users.referral_claims SET claimed_at = now() - interval '31 days' WHERE"
                + " referee_user_id = :u")
        .param("u", oldReferee)
        .update();

    referrals.purgeExpiredIps();

    assertThat(activity(referrer))
        .extracting(r -> r.get("ip_hash"))
        .containsExactly(hashOf("203.0.113.10"));
    assertThat(claimRows(oldReferee)).isEqualTo(1);
    assertThat(claimHash(oldReferee)).isNull();
    assertThat(claimHash(newReferee)).isEqualTo(hashOf("198.51.100.5"));
  }

  @Test
  @DisplayName("retention job is a no-op when there is nothing to purge")
  void purgeNoOp() {
    referrals.purgeExpiredIps();
    referrals.purgeExpiredIps();
  }
}
