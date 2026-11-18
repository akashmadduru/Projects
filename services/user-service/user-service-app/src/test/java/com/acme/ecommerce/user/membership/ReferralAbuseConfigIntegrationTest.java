package com.acme.ecommerce.user.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.user.config.UserProperties;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** AC-8: the thresholds come from settings, not constants. */
@TestPropertySource(
    properties = {
      "user.referral.min-code-age=2h",
      "user.referral.ip-lookback=1d",
      "user.referral.ip-retention=1d"
    })
class ReferralAbuseConfigIntegrationTest extends AbstractUserIntegrationTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private UserProperties properties;
  @Autowired private ReferralService referrals;
  @Autowired private IpFingerprint fingerprint;

  private static Optional<InetAddress> ip(String literal) throws Exception {
    return Optional.of(InetAddress.getByName(literal));
  }

  private void codeAge(UUID referrer, String interval) {
    jdbc.sql(
            "UPDATE users.referral_codes SET created_at = now() - CAST(:i AS interval) WHERE"
                + " user_id = :u")
        .param("i", interval)
        .param("u", referrer)
        .update();
  }

  private void activityAge(UUID referrer, String interval) {
    jdbc.sql(
            "UPDATE users.referral_ip_activity SET last_seen_at = now() - CAST(:i AS interval)"
                + " WHERE user_id = :u")
        .param("i", interval)
        .param("u", referrer)
        .update();
  }

  private int activityRows(UUID referrer) {
    return jdbc.sql("SELECT count(*)::int FROM users.referral_ip_activity WHERE user_id = :u")
        .param("u", referrer)
        .query(Integer.class)
        .single();
  }

  @Test
  @DisplayName("the overridden values bind")
  void binds() {
    UserProperties.Referral r = properties.getReferral();
    assertThat(r.getMinCodeAge()).isEqualTo(Duration.ofHours(2));
    assertThat(r.getIpLookback()).isEqualTo(Duration.ofDays(1));
    assertThat(r.getIpRetention()).isEqualTo(Duration.ofDays(1));
  }

  @Test
  @DisplayName("a code aged 1h is refused and one aged 3h passes")
  void minCodeAgeFromConfig() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    codeAge(referrer, "1 hour");
    assertThatThrownBy(() -> referrals.claim(UUID.randomUUID(), code, ip("198.51.100.4")))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFERRAL_CODE_TOO_NEW));
    codeAge(referrer, "3 hours");
    UUID referee = UUID.randomUUID();
    referrals.claim(referee, code, ip("198.51.100.4"));
    assertThat(referrals.byReferee(referee)).isPresent();
  }

  @Test
  @DisplayName("activity 2d old is ignored and 12h old still matches")
  void lookbackFromConfig() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    codeAge(referrer, "1 day");
    activityAge(referrer, "12 hours");
    assertThatThrownBy(() -> referrals.claim(UUID.randomUUID(), code, ip("203.0.113.9")))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFERRAL_IP_MATCH));
    activityAge(referrer, "2 days");
    UUID referee = UUID.randomUUID();
    referrals.claim(referee, code, ip("203.0.113.9"));
    assertThat(referrals.byReferee(referee)).isPresent();
  }

  @Test
  @DisplayName("the purge uses the configured retention: 2d old goes, 12h old stays")
  void retentionFromConfig() throws Exception {
    UUID oldUser = UUID.randomUUID();
    UUID freshUser = UUID.randomUUID();
    referrals.view(oldUser, ip("203.0.113.9"));
    referrals.view(freshUser, ip("203.0.113.9"));
    activityAge(oldUser, "2 days");
    activityAge(freshUser, "12 hours");
    referrals.purgeExpiredIps();
    assertThat(activityRows(oldUser)).isZero();
    assertThat(activityRows(freshUser)).isEqualTo(1);
    assertThat(fingerprint.hash(InetAddress.getByName("203.0.113.9"))).hasSize(64);
  }
}
