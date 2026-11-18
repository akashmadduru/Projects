package com.acme.ecommerce.user.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.user.privacy.PrivacyKafkaConfig.PrivacyHttp;
import com.acme.ecommerce.user.privacy.PrivacyService;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** TD-P8-13: hashed IPs are part of account deletion. */
class ReferralAccountDeletionIntegrationTest extends AbstractUserIntegrationTest {

  @MockitoBean private PrivacyHttp privacyHttp;

  @MockitoBean(name = "privacyKafkaTemplate")
  private KafkaTemplate<String, String> kafka;

  @Autowired private ReferralService referrals;
  @Autowired private PrivacyService privacy;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("completing a deletion removes the user's activity rows and nulls their claim hash")
  void deletionClearsIpHashes() throws Exception {
    when(privacyHttp.get(anyString(), anyString(), any(Object[].class))).thenReturn("{}");
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));

    UUID user = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    String code = referrals.view(user, Optional.of(InetAddress.getByName("203.0.113.9"))).code();
    String otherCode =
        referrals.view(other, Optional.of(InetAddress.getByName("203.0.113.9"))).code();
    UUID referee = UUID.randomUUID();
    jdbc.sql("UPDATE users.referral_codes SET created_at = now() - interval '1 day'").update();
    referrals.claim(user, otherCode, Optional.of(InetAddress.getByName("198.51.100.4")));
    referrals.claim(referee, code, Optional.of(InetAddress.getByName("198.51.100.5")));

    jdbc.sql(
            "INSERT INTO users.deletion_requests (user_id, status, scheduled_for) VALUES (:u,"
                + " 'SCHEDULED', now() - interval '1 minute')")
        .param("u", user)
        .update();
    assertThat(privacy.runDue(Instant.now())).isEqualTo(1);

    assertThat(count("referral_ip_activity", "user_id", user)).isZero();
    assertThat(count("referral_ip_activity", "user_id", other)).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT claim_ip_hash FROM users.referral_claims WHERE referee_user_id = :u")
                .param("u", user)
                .query()
                .singleRow()
                .get("claim_ip_hash"))
        .isNull();
    assertThat(
            jdbc.sql("SELECT claim_ip_hash FROM users.referral_claims WHERE referee_user_id = :u")
                .param("u", referee)
                .query()
                .singleRow()
                .get("claim_ip_hash"))
        .isNotNull();
  }

  private int count(String table, String column, UUID id) {
    return jdbc.sql("SELECT count(*)::int FROM users." + table + " WHERE " + column + " = :u")
        .param("u", id)
        .query(Integer.class)
        .single();
  }
}
