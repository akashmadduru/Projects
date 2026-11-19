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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** AC-4 and AC-5: per-phone window cap and per-day cap on OTP sends. */
@Import(FixedClockTestConfig.class)
class OtpCapsIT extends AbstractAuthIntegrationTest {

  private static final String PHONE = "+919876543210";
  private static final int CAPACITY = 3;
  private static final int DAILY = 10;
  private static final Duration PAST_COOLDOWN = Duration.ofSeconds(31);

  @Autowired private MockMvc mockMvc;
  @Autowired private FixedTimeProvider time;
  @Autowired private StringRedisTemplate redis;
  @Autowired private RedisReset redisReset;
  @Autowired private UserFixtures userFixtures;
  @Autowired private UserAccountRepository users;
  @Autowired private DatabaseCleaner databaseCleaner;
  @MockitoBean private NotificationGateway notifications;

  @BeforeEach
  void setUp() {
    databaseCleaner.clean();
    redisReset.flush();
    time.setTo(FixedClockTestConfig.START);
    reset(notifications);
    UserAccount user =
        userFixtures.createPasswordUser("otp@example.com", "pw-correct-horse-1", Roles.ROLE_ADMIN);
    UserAccount loaded = users.findById(user.getId()).orElseThrow();
    loaded.changePhone(PHONE);
    loaded.markPhoneVerified();
    users.saveAndFlush(loaded);
  }

  private MockHttpServletResponse request(String phone) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"))
        .andReturn()
        .getResponse();
  }

  private String windowKey() {
    return "auth:otp:phone:"
        + PHONE
        + ":"
        + (time.epochMilli() / Duration.ofMinutes(15).toMillis());
  }

  @Test
  @DisplayName("request cap+1 is the same 202 with no SMS and the phone counter exceeds the cap")
  void windowCap() throws Exception {
    MockHttpServletResponse normal = null;
    MockHttpServletResponse capped = null;
    for (int i = 1; i <= CAPACITY + 1; i++) {
      time.advanceBy(PAST_COOLDOWN);
      MockHttpServletResponse response = request(PHONE);
      if (i == 1) {
        normal = response;
      }
      capped = response;
    }

    assertThat(capped.getStatus()).isEqualTo(202).isEqualTo(normal.getStatus());
    assertThat(capped.getContentAsString()).isEqualTo(normal.getContentAsString()).isEmpty();
    assertThat(capped.getHeaderNames()).isEqualTo(normal.getHeaderNames());
    verify(notifications, times(CAPACITY)).sendSms(eq(PHONE), anyString());
    assertThat(Long.parseLong(redis.opsForValue().get(windowKey()))).isGreaterThan(CAPACITY);
  }

  @Test
  @DisplayName("three phone formats share one counter key")
  void formatsShareOneKey() throws Exception {
    request("+91 98765 43210");
    request("+91-98765-43210");
    request("(+91)9876543210");

    assertThat(redis.opsForValue().get(windowKey())).isEqualTo("3");
    assertThat(redis.keys("auth:otp:phone:*")).hasSize(1);
  }

  @Test
  @DisplayName("D sends per day, send D+1 is skipped, and after 24h a request sends again")
  void dailyCap() throws Exception {
    for (int i = 1; i <= DAILY + 1; i++) {
      time.advanceBy(Duration.ofMinutes(16)); // past cooldown and into a fresh 15-minute window
      assertThat(request(PHONE).getStatus()).isEqualTo(202);
    }
    verify(notifications, times(DAILY)).sendSms(eq(PHONE), anyString());

    time.advanceBy(Duration.ofHours(24));
    assertThat(request(PHONE).getStatus()).isEqualTo(202);
    verify(notifications, times(DAILY + 1)).sendSms(eq(PHONE), anyString());
  }

  @Test
  @DisplayName("an unknown phone gets the identical 202 (and is counted the same way)")
  void unknownPhoneSameResponse() throws Exception {
    MockHttpServletResponse known = request(PHONE);
    MockHttpServletResponse unknown = request("+919000000001");

    assertThat(unknown.getStatus()).isEqualTo(known.getStatus()).isEqualTo(202);
    assertThat(unknown.getContentAsString()).isEqualTo(known.getContentAsString()).isEmpty();
    assertThat(unknown.getHeaderNames()).isEqualTo(known.getHeaderNames());
    assertThat(redis.keys("auth:otp:phone:*")).hasSize(2);
  }
}
