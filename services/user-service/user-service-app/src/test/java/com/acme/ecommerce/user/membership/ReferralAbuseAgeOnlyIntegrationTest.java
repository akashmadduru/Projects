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

/** AC-9: each check can be switched off on its own. */
@TestPropertySource(properties = {"user.referral.ip-lookback=0s"})
class ReferralAbuseAgeOnlyIntegrationTest extends AbstractUserIntegrationTest {

  @Autowired private ReferralService referrals;

  private static Optional<InetAddress> ip(String literal) throws Exception {
    return Optional.of(InetAddress.getByName(literal));
  }

  @Test
  @DisplayName("lookback 0 keeps the age check but ignores the network")
  void ipCheckOffAgeCheckOn() throws Exception {
    UUID referrer = UUID.randomUUID();
    String code = referrals.view(referrer, ip("203.0.113.9")).code();
    assertThatThrownBy(() -> referrals.claim(UUID.randomUUID(), code, ip("198.51.100.4")))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFERRAL_CODE_TOO_NEW));
  }
}
