package com.acme.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.FixedClockTestConfig;
import com.acme.ecommerce.auth.support.RedisReset;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import com.acme.ecommerce.common.security.Roles;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** A cap of 0 disables that cap (kill switch). */
@SpringBootTest(
    properties = {
      "auth.rate-limit.otp-per-phone-capacity=0",
      "auth.rate-limit.otp-per-phone-daily=0"
    })
@Import(FixedClockTestConfig.class)
class OtpCapsDisabledIT extends AbstractAuthIntegrationTest {

  private static final String PHONE = "+919876543211";

  @Autowired private MockMvc mockMvc;
  @Autowired private FixedTimeProvider time;
  @Autowired private StringRedisTemplate redis;
  @Autowired private RedisReset redisReset;
  @Autowired private UserFixtures userFixtures;
  @Autowired private UserAccountRepository users;
  @Autowired private DatabaseCleaner databaseCleaner;
  @MockitoBean private NotificationGateway notifications;

  @Test
  @DisplayName("capacity 0 and daily 0 send every time (past the cooldown) and write no counter")
  void zeroDisablesCaps() throws Exception {
    databaseCleaner.clean();
    redisReset.flush();
    time.setTo(FixedClockTestConfig.START);
    reset(notifications);
    UserAccount user =
        userFixtures.createPasswordUser("off@example.com", "pw-correct-horse-1", Roles.ROLE_ADMIN);
    UserAccount loaded = users.findById(user.getId()).orElseThrow();
    loaded.changePhone(PHONE);
    loaded.markPhoneVerified();
    users.saveAndFlush(loaded);

    for (int i = 0; i < 12; i++) {
      time.advanceBy(Duration.ofSeconds(31));
      mockMvc.perform(
          post("/api/auth/otp/request")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"phone\":\"" + PHONE + "\"}"));
    }

    verify(notifications, times(12)).sendSms(eq(PHONE), anyString());
    assertThat(redis.keys("auth:otp:*")).isEmpty();
  }
}
