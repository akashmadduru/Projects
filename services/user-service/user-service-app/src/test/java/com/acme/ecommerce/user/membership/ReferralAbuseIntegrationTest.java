package com.acme.ecommerce.user.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** TD-P8-13: referral abuse checks, against a real Postgres. */
@TestPropertySource(properties = "user.referral.ip-lookback=30d")
class ReferralAbuseIntegrationTest extends AbstractUserIntegrationTest {

  private static final String HEX64 = "^[0-9a-f]{64}$";

  @Autowired private JdbcClient jdbc;
  @Autowired private ReferralService referrals;
  @Autowired private IpFingerprint fingerprint;

  private static InetAddress ip(String literal) throws Exception {
    return InetAddress.getByName(literal);
  }

  private static InetAddress safeIp(String literal) {
    try {
      return ip(literal);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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

  /** Makes the referrer's code old enough to pass the default minimum-age check. */
  private void age(UUID referrer) {
    setCodeAge(referrer, "1 day");
  }

  private void setCodeAge(UUID referrer, String interval) {
    jdbc.sql(
            "UPDATE users.referral_codes SET created_at = now() - CAST(:i AS interval) WHERE"
                + " user_id = :u")
        .param("i", interval)
        .param("u", referrer)
        .update();
  }

  private void backdateActivity(UUID referrer, String interval) {
    jdbc.sql(
            "UPDATE users.referral_ip_activity SET last_seen_at = now() - CAST(:i AS interval)"
                + " WHERE user_id = :u")
        .param("i", interval)
        .param("u", referrer)
        .update();
  }

  private static void assertRefused(Runnable call, ErrorCode expected) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            CodedException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
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
    referrals.view(user, null);
    assertThat(activity(user)).hasSize(2);
  }

  @Test
  @DisplayName("claim stores the hash only; no column holds the raw IP")
  void claimStoresHashOnly() throws Exception {
    UUID referrer = UUID.randomUUID();
    UUID referee = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    age(referrer);
    referrals.claim(referee, code, ip("198.51.100.4"));

    assertThat(claimHash(referee)).matches(HEX64).isEqualTo(hashOf("198.51.100.4"));
    for (String table : List.of("referral_ip_activity", "referral_claims")) {
      List<Map<String, Object>> rows =
          jdbc.sql("SELECT * FROM users." + table).query().listOfRows();
      assertThat(rows.toString()).doesNotContain("203.0.113.9").doesNotContain("198.51.100.4");
    }
    UUID noIp = UUID.randomUUID();
    referrals.claim(noIp, code, null);
    assertThat(claimRows(noIp)).isEqualTo(1);
    assertThat(claimHash(noIp)).isNull();
  }

  @Test
  @DisplayName("only the 20 most recent hashed IPs are kept per user, in the same transaction")
  void activityCapKeepsMostRecent() throws Exception {
    UUID user = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    referrals.view(other, ip("198.51.100.1"));
    for (int i = 1; i <= 25; i++) {
      referrals.view(user, ip("10.0.0." + i));
      // make this row strictly older than the next insert so recency is unambiguous
      jdbc.sql(
              "UPDATE users.referral_ip_activity SET last_seen_at = now() - (CAST(:age AS int) *"
                  + " interval '1 minute') WHERE user_id = :u AND ip_hash = :h")
          .param("age", 100 - i)
          .param("u", user)
          .param("h", hashOf("10.0.0." + i))
          .update();
    }
    assertThat(activity(user)).hasSize(ReferralService.MAX_ACTIVITY_ROWS);
    assertThat(activity(user))
        .extracting(r -> r.get("ip_hash"))
        .contains(hashOf("10.0.0.25"), hashOf("10.0.0.6"))
        .doesNotContain(hashOf("10.0.0.1"), hashOf("10.0.0.5"));
    assertThat(activity(other)).hasSize(1);
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
    String code = referrals.view(referrer, null).code();
    age(referrer);
    assertThatThrownBy(() -> referrals.claim(referrer, code, null))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DOMAIN_RULE_VIOLATION));
    referrals.claim(referee, code, null);
    assertThatThrownBy(() -> referrals.claim(referee, code, null))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
  }

  @Test
  @DisplayName("retention job deletes old activity and nulls old claim hashes, keeping claims")
  void purgeRemovesOnlyExpired() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    age(referrer);
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
  @DisplayName("AC-1: an aged code and a different network is accepted")
  void agedCodeDifferentIpAccepted() throws Exception {
    UUID referrer = UUID.randomUUID();
    UUID referee = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    age(referrer);
    referrals.claim(referee, code, ip("198.51.100.4"));
    assertThat(claimRows(referee)).isEqualTo(1);
  }

  @Test
  @DisplayName("AC-2: a young code is refused with no row")
  void youngCodeRefused() throws Exception {
    UUID referrer = UUID.randomUUID();
    UUID referee = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    assertRefused(
        () -> referrals.claim(referee, code, safeIp("198.51.100.4")),
        ErrorCode.REFERRAL_CODE_TOO_NEW);
    assertThat(claimRows(referee)).isZero();
  }

  @Test
  @DisplayName("AC-3 and AC-15: the referrer's network is refused, however the IPv6 is written")
  void sameIpRefused() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    String code6 = referrals.view(referrer, ip("2001:DB8::1")).code();
    age(referrer);
    UUID referee = UUID.randomUUID();
    assertRefused(
        () -> referrals.claim(referee, code, safeIp("203.0.113.9")), ErrorCode.REFERRAL_IP_MATCH);
    assertRefused(
        () -> referrals.claim(referee, code6, safeIp("2001:0db8:0:0:0:0:0:1")),
        ErrorCode.REFERRAL_IP_MATCH);
    assertRefused(
        () -> referrals.claim(referee, code6, safeIp("2001:db8::1%1")),
        ErrorCode.REFERRAL_IP_MATCH);
    assertThat(claimRows(referee)).isZero();
  }

  @Test
  @DisplayName("AC-4: activity older than the lookback is ignored")
  void oldActivityIgnored() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    age(referrer);
    backdateActivity(referrer, "31 days");
    UUID referee = UUID.randomUUID();
    referrals.claim(referee, code, ip("203.0.113.9"));
    assertThat(claimRows(referee)).isEqualTo(1);
  }

  @Test
  @DisplayName("AC-10: a young code with the same IP reports only the code age")
  void ageCheckBeforeIpCheck() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    assertRefused(
        () -> referrals.claim(UUID.randomUUID(), code, safeIp("203.0.113.9")),
        ErrorCode.REFERRAL_CODE_TOO_NEW);
  }

  @Test
  @DisplayName("AC-11: a refusal does not burn the referee's one claim")
  void refusalKeepsSlotFree() throws Exception {
    UUID referrer = UUID.randomUUID();
    UUID referrer2 = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    String code2 = referrals.view(referrer2, ip("192.0.2.77")).code();
    age(referrer2);
    UUID referee = UUID.randomUUID();
    assertRefused(
        () -> referrals.claim(referee, code, safeIp("198.51.100.4")),
        ErrorCode.REFERRAL_CODE_TOO_NEW);
    age(referrer);
    referrals.claim(referee, code, ip("198.51.100.4"));
    assertThat(claimRows(referee)).isEqualTo(1);

    UUID referee2 = UUID.randomUUID();
    assertRefused(
        () -> referrals.claim(referee2, code2, safeIp("192.0.2.77")), ErrorCode.REFERRAL_IP_MATCH);
    referrals.claim(referee2, code, ip("192.0.2.77"));
    assertThat(claimRows(referee2)).isEqualTo(1);
  }

  @Test
  @DisplayName("a lower-case, padded code hits the same checks")
  void normalisedCodeChecked() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    assertRefused(
        () ->
            referrals.claim(UUID.randomUUID(), "  " + code.toLowerCase() + " ", safeIp("1.2.3.4")),
        ErrorCode.REFERRAL_CODE_TOO_NEW);
  }

  @Test
  @DisplayName("AC-12: a duplicate claim is 409 even while the code is young")
  void duplicateBeforeAgeCheck() throws Exception {
    UUID referrer = UUID.randomUUID();
    UUID referee = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    age(referrer);
    referrals.claim(referee, code, ip("198.51.100.4"));
    setCodeAge(referrer, "1 minute");
    assertRefused(
        () -> referrals.claim(referee, code, safeIp("203.0.113.9")), ErrorCode.RESOURCE_CONFLICT);
  }

  @Test
  @DisplayName("two concurrent claims by one referee leave exactly one row")
  void concurrentClaims() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    age(referrer);
    UUID referee = UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    referrals.claim(referee, code, ip("198.51.100.4"));
                    return true;
                  } catch (CodedException e) {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    return false;
                  }
                }));
      }
      go.countDown();
      long ok = 0;
      for (Future<Boolean> f : results) {
        if (f.get(30, TimeUnit.SECONDS)) {
          ok++;
        }
      }
      assertThat(ok).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(claimRows(referee)).isEqualTo(1);
  }
}
