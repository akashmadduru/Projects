package com.acme.ecommerce.user.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** AC-9: a zero age or lookback switches that check off, each independently of the other. */
@TestPropertySource(properties = {"user.referral.min-code-age=0s", "user.referral.ip-lookback=0s"})
class ReferralAbuseDisabledChecksIntegrationTest extends AbstractUserIntegrationTest {

  @Autowired private ReferralService referrals;

  private static Optional<InetAddress> ip(String literal) throws Exception {
    return Optional.of(InetAddress.getByName(literal));
  }

  @Test
  @DisplayName("with both checks off, a young code from the same network is accepted")
  void bothOff() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    UUID referee = UUID.randomUUID();
    referrals.claim(referee, code, ip("203.0.113.9"));
    assertThat(referrals.byReferee(referee)).isPresent();
  }

  @Test
  @DisplayName("existing rules still apply with the checks off")
  void existingRulesStillApply() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    assertThatThrownBy(() -> referrals.claim(referrer, code, ip("203.0.113.9")))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DOMAIN_RULE_VIOLATION));
  }
}
