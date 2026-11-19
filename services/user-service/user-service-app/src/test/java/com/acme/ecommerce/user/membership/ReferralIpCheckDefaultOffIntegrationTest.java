package com.acme.ecommerce.user.membership;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.user.config.UserProperties;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Owner decision (round 4): with default config the IP check is off and nothing is recorded. */
class ReferralIpCheckDefaultOffIntegrationTest extends AbstractUserIntegrationTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private ReferralService referrals;
  @Autowired private UserProperties properties;

  private int rows(String table, String column, UUID user) {
    return jdbc.sql("SELECT count(*)::int FROM users." + table + " WHERE " + column + " = :u")
        .param("u", user)
        .query(Integer.class)
        .single();
  }

  @Test
  @DisplayName("default lookback is 0: same-IP claim succeeds and no ip_activity row is written")
  void sameIpClaimSucceedsAndNothingRecorded() throws Exception {
    assertThat(properties.getReferral().getIpLookback()).isEqualTo(Duration.ZERO);
    InetAddress same = InetAddress.getByName("203.0.113.9");
    UUID referrer = UUID.randomUUID();
    UUID referee = UUID.randomUUID();
    String code = referrals.view(referrer, same).code();
    jdbc.sql(
            "UPDATE users.referral_codes SET created_at = now() - interval '1 day' WHERE code = :c")
        .param("c", code)
        .update();

    referrals.claim(referee, code, same);

    assertThat(rows("referral_claims", "referee_user_id", referee)).isEqualTo(1);
    assertThat(rows("referral_ip_activity", "user_id", referrer)).isZero();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM users.referral_claims WHERE referee_user_id = :u AND"
                        + " claim_ip_hash IS NULL")
                .param("u", referee)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }
}
