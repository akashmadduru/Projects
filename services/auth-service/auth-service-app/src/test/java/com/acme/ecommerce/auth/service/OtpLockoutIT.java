package com.acme.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.api.dto.LoginRequest;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.domain.UserCredential;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.repository.UserCredentialRepository;
import com.acme.ecommerce.auth.service.OneTimeTokenService.Purpose;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.FixedClockTestConfig;
import com.acme.ecommerce.auth.support.RedisReset;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.auth.token.DeviceContext;
import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import com.acme.ecommerce.common.security.Roles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** AC-6..AC-9: OTP failures feed the account lockout; unknown phones and OTP-only users do not. */
@Import(FixedClockTestConfig.class)
class OtpLockoutIT extends AbstractAuthIntegrationTest {

  private static final String EMAIL = "lock@example.com";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String PHONE = "+919876500001";
  private static final String UNVERIFIED_PHONE = "+919876500002";
  private static final String UNKNOWN_PHONE = "+919876500003";
  private static final String OTP_ONLY_PHONE = "+919876500004";

  @Autowired private OtpLoginService otpLogin;
  @Autowired private LoginService loginService;
  @Autowired private OneTimeTokenService tokens;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private FixedTimeProvider time;
  @Autowired private RedisReset redisReset;
  @Autowired private UserFixtures userFixtures;
  @Autowired private UserAccountRepository users;
  @Autowired private UserCredentialRepository credentials;
  @Autowired private DatabaseCleaner databaseCleaner;

  private UserAccount credentialUser;
  private UserAccount otpOnlyUser;

  @BeforeEach
  void setUp() {
    databaseCleaner.clean();
    redisReset.flush();
    time.setTo(FixedClockTestConfig.START);
    credentialUser = userFixtures.createPasswordUser(EMAIL, PASSWORD, Roles.ROLE_ADMIN);
    setPhone(credentialUser.getId(), PHONE, true);
    UserAccount unverified =
        userFixtures.createPasswordUser("unverified@example.com", PASSWORD, Roles.ROLE_ADMIN);
    setPhone(unverified.getId(), UNVERIFIED_PHONE, false);
    otpOnlyUser = userFixtures.createSocialOnlyUser("social@example.com", Roles.ROLE_ADMIN);
    setPhone(otpOnlyUser.getId(), OTP_ONLY_PHONE, true);
  }

  private void setPhone(UUID userId, String phone, boolean verified) {
    UserAccount user = users.findById(userId).orElseThrow();
    user.changePhone(phone);
    if (verified) {
      user.markPhoneVerified();
    }
    users.saveAndFlush(user);
  }

  private static DeviceContext device() {
    return new DeviceContext("JUnit/1.0", "198.51.100.4");
  }

  /** Issues a fresh code (replacing any earlier one, no cooldown) and returns it. */
  private String freshCode(UUID userId, String phone) {
    return tokens
        .issueCode(userId, Purpose.OTP_LOGIN, phone, Duration.ofMinutes(5), Duration.ZERO)
        .orElseThrow();
  }

  private static String wrong(String code) {
    return "000000".equals(code) ? "000001" : "000000";
  }

  private void wrongOtp(UUID userId, String phone) {
    String bad = wrong(freshCode(userId, phone));
    assertThatThrownBy(() -> otpLogin.verify(phone, bad, device()))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> assertThat(e.authErrorCode()).isEqualTo(AuthErrorCode.AUTH_OTP_INVALID));
  }

  private AuthErrorCode codeOf(Runnable action) {
    try {
      action.run();
    } catch (AuthException e) {
      return e.authErrorCode();
    }
    return null;
  }

  @Test
  @DisplayName("5 wrong OTPs lock the account; correct OTP and correct password both get LOCKED")
  void fiveWrongOtpsLock() {
    for (int i = 0; i < 5; i++) {
      wrongOtp(credentialUser.getId(), PHONE);
    }

    UserCredential credential = credentials.findById(credentialUser.getId()).orElseThrow();
    assertThat(credential.getFailedAttempts()).isEqualTo((short) 5);
    assertThat(credential.getLockedUntil()).isNotNull();

    String good = freshCode(credentialUser.getId(), PHONE);
    assertThat(codeOf(() -> otpLogin.verify(PHONE, good, device())))
        .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
    assertThat(codeOf(() -> loginService.login(new LoginRequest(EMAIL, PASSWORD), device())))
        .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
  }

  @Test
  @DisplayName("wrong OTPs during an active lock do not move locked_until; response stays INVALID")
  void wrongOtpsDoNotRenewLock() {
    for (int i = 0; i < 5; i++) {
      wrongOtp(credentialUser.getId(), PHONE);
    }
    UserCredential locked = credentials.findById(credentialUser.getId()).orElseThrow();
    java.time.Instant until = locked.getLockedUntil();
    short failures = locked.getFailedAttempts();

    for (int i = 0; i < 3; i++) {
      wrongOtp(credentialUser.getId(), PHONE);
    }

    UserCredential after = credentials.findById(credentialUser.getId()).orElseThrow();
    assertThat(after.getLockedUntil()).isEqualTo(until);
    assertThat(after.getFailedAttempts()).isEqualTo(failures);
  }

  @Test
  @DisplayName("after locked_until passes, OTP login succeeds and the failure count resets")
  void successResetsCounters() {
    for (int i = 0; i < 5; i++) {
      wrongOtp(credentialUser.getId(), PHONE);
    }
    time.advanceBy(Duration.ofMinutes(2));

    String good = freshCode(credentialUser.getId(), PHONE);
    assertThat(otpLogin.verify(PHONE, good, device())).isNotNull();

    UserCredential credential = credentials.findById(credentialUser.getId()).orElseThrow();
    assertThat(credential.getFailedAttempts()).isZero();
    assertThat(credential.getLockedUntil()).isNull();
  }

  private MockHttpServletResponse verifyHttp(String phone, String code) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
        .andReturn()
        .getResponse();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> stableBody(MockHttpServletResponse response) throws Exception {
    Map<String, Object> body = objectMapper.readValue(response.getContentAsString(), Map.class);
    body.remove("correlationId");
    body.remove("timestamp");
    return body;
  }

  @Test
  @DisplayName(
      "unknown and unverified phones: 10 wrong OTPs, same AUTH_OTP_INVALID body, no row changes")
  void unknownAndUnverifiedPhonesAreIndistinguishableAndRecordNothing() throws Exception {
    freshCode(credentialUser.getId(), PHONE);
    MockHttpServletResponse known =
        verifyHttp(PHONE, wrong(freshCode(credentialUser.getId(), PHONE)));
    assertThat(known.getStatus()).isEqualTo(401);
    Map<String, Object> reference = stableBody(known);
    assertThat(reference).containsEntry("code", "AUTH_OTP_INVALID");
    int knownFailures =
        credentials.findById(credentialUser.getId()).orElseThrow().getFailedAttempts();
    assertThat(knownFailures).isEqualTo(1);

    for (int i = 0; i < 10; i++) {
      for (String phone : new String[] {UNKNOWN_PHONE, UNVERIFIED_PHONE}) {
        MockHttpServletResponse response = verifyHttp(phone, "123456");
        assertThat(response.getStatus()).isEqualTo(known.getStatus());
        assertThat(stableBody(response)).isEqualTo(reference);
        assertThat(response.getHeaderNames()).isEqualTo(known.getHeaderNames());
      }
    }

    assertThat(credentials.findAll())
        .allSatisfy(
            c -> {
              if (c.getUserId().equals(credentialUser.getId())) {
                assertThat((int) c.getFailedAttempts()).isEqualTo(knownFailures);
              } else {
                assertThat(c.getFailedAttempts()).isZero();
                assertThat(c.getLockedUntil()).isNull();
              }
            });
  }

  @Test
  @DisplayName("OTP-only user (no credential row): never locked, no error beyond AUTH_OTP_INVALID")
  void otpOnlyUserIsNeverLocked() throws Exception {
    MockHttpServletResponse first =
        verifyHttp(OTP_ONLY_PHONE, wrong(freshCode(otpOnlyUser.getId(), OTP_ONLY_PHONE)));
    assertThat(first.getStatus()).isEqualTo(401);
    Map<String, Object> reference = stableBody(first);
    assertThat(reference).containsEntry("code", "AUTH_OTP_INVALID");

    for (int i = 0; i < 10; i++) {
      MockHttpServletResponse response =
          verifyHttp(OTP_ONLY_PHONE, wrong(freshCode(otpOnlyUser.getId(), OTP_ONLY_PHONE)));
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(stableBody(response)).isEqualTo(reference);
    }

    assertThat(credentials.findById(otpOnlyUser.getId())).isEmpty();
    // And the owner, holding a valid code, still gets in: there is nothing to be locked by.
    String good = freshCode(otpOnlyUser.getId(), OTP_ONLY_PHONE);
    assertThat(otpLogin.verify(OTP_ONLY_PHONE, good, device())).isNotNull();
  }
}
