package com.acme.ecommerce.user.membership;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.user.config.UserProperties;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** TD-P8-13: referral abuse checks, against a real Postgres. */
class ReferralAbuseIntegrationTest extends AbstractUserIntegrationTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private UserProperties properties;

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
}
