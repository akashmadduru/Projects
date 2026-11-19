package com.acme.ecommerce.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.acme.ecommerce.auth.api.dto.LoginRequest;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.service.NotificationGateway;
import com.acme.ecommerce.auth.service.OneTimeTokenService;
import com.acme.ecommerce.auth.service.OneTimeTokenService.Purpose;
import com.acme.ecommerce.auth.service.PhoneClaimNotifier;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.common.security.Roles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** AC-9 and AC-11: add-phone and phone verify answer the same for taken and free numbers. */
class PhoneEnumerationIntegrationTest extends AbstractAuthIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String TAKEN = "+919876500001";
  private static final String FREE = "+919876500002";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserFixtures userFixtures;
  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private UserAccountRepository users;
  @Autowired private OneTimeTokenService tokens;
  @Autowired private TransactionTemplate tx;
  @MockitoBean private NotificationGateway notifications;
  @MockitoBean private PhoneClaimNotifier claimNotifier;

  private UserAccount userA;
  private UserAccount userB;

  @BeforeEach
  void setUp() {
    databaseCleaner.clean();
    reset(notifications, claimNotifier);
    UserAccount owner =
        userFixtures.createPasswordUser("owner@example.com", PASSWORD, Roles.ROLE_VIEWER);
    tx.executeWithoutResult(
        s -> {
          UserAccount o = users.findById(owner.getId()).orElseThrow();
          o.changePhone(TAKEN);
          o.markPhoneVerified();
        });
    userA = userFixtures.createPasswordUser("a@example.com", PASSWORD, Roles.ROLE_VIEWER);
    userB = userFixtures.createPasswordUser("b@example.com", PASSWORD, Roles.ROLE_VIEWER);
  }

  @Test
  @DisplayName("taken and free numbers give identical status, body and headers; no SMS for taken")
  void addPhoneIsIdenticalForTakenAndFree() throws Exception {
    MockHttpServletResponse taken = addPhone(userA, TAKEN);
    MockHttpServletResponse free = addPhone(userB, FREE);

    assertThat(taken.getStatus()).isEqualTo(202);
    assertThat(free.getStatus()).isEqualTo(202);
    assertThat(taken.getContentAsString()).isEmpty();
    assertThat(free.getContentAsString()).isEmpty();
    assertThat(comparableHeaders(taken)).isEqualTo(comparableHeaders(free));

    verify(notifications, never()).sendSms(eq(TAKEN), anyString());
    verify(notifications, times(1)).sendSms(eq(FREE), anyString());
    verify(claimNotifier, times(1)).attempted(TAKEN);
    verify(claimNotifier, never()).attempted(FREE);
  }

  @Test
  @DisplayName("a resend within the cooldown is the same 429 for taken and free numbers")
  void resendIsRateLimitedIdentically() throws Exception {
    addPhone(userA, TAKEN);
    addPhone(userB, FREE);

    MockHttpServletResponse taken = addPhone(userA, TAKEN);
    MockHttpServletResponse free = addPhone(userB, FREE);

    assertThat(taken.getStatus()).isEqualTo(429);
    assertThat(free.getStatus()).isEqualTo(429);
    assertThat(json(taken)).containsEntry("code", "AUTH_RATE_LIMITED");
    assertThat(json(free)).containsEntry("code", "AUTH_RATE_LIMITED");
    verify(claimNotifier, times(1)).attempted(TAKEN);
  }

  @Test
  @DisplayName("a decoy code for a taken number is AUTH_OTP_INVALID and the phone stays unverified")
  void decoyCodeForTakenNumberIsRejected() throws Exception {
    addPhone(userA, TAKEN);
    String decoy =
        tokens
            .issueCode(
                userA.getId(), Purpose.PHONE_VERIFY, TAKEN, Duration.ofMinutes(10), Duration.ZERO)
            .orElseThrow();

    MockHttpServletResponse response = verifyPhone(userA, decoy);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(json(response)).containsEntry("code", "AUTH_OTP_INVALID");
    assertThat(users.findById(userA.getId()).orElseThrow().isPhoneVerified()).isFalse();
  }

  @Test
  @DisplayName("race: both add a free number, B verifies first, A gets 401 AUTH_OTP_INVALID")
  void lostRaceIsAnInvalidCode() throws Exception {
    assertThat(addPhone(userA, FREE).getStatus()).isEqualTo(202);
    String codeA = lastSmsCode();
    assertThat(addPhone(userB, FREE).getStatus()).isEqualTo(202);
    String codeB = lastSmsCode();

    assertThat(verifyPhone(userB, codeB).getStatus()).isEqualTo(204);
    MockHttpServletResponse lost = verifyPhone(userA, codeA);

    assertThat(lost.getStatus()).isEqualTo(401);
    assertThat(json(lost)).containsEntry("code", "AUTH_OTP_INVALID");
    assertThat(users.findById(userB.getId()).orElseThrow().isPhoneVerified()).isTrue();
    assertThat(users.findById(userA.getId()).orElseThrow().isPhoneVerified()).isFalse();
  }

  @Test
  @DisplayName("unique index backs the re-check: a second verified row for one number is refused")
  void uniqueIndexStillRefusesDuplicateVerifiedNumbers() {
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            tx.executeWithoutResult(
                s -> {
                  UserAccount a = users.findById(userA.getId()).orElseThrow();
                  a.changePhone(TAKEN);
                  a.markPhoneVerified();
                  users.flush();
                }));
  }

  @Test
  @DisplayName("free number happy path is unchanged: 202, SMS, 204, verified (AC-11)")
  void freeNumberHappyPath() throws Exception {
    assertThat(addPhone(userB, FREE).getStatus()).isEqualTo(202);
    String code = lastSmsCode();

    assertThat(verifyPhone(userB, code).getStatus()).isEqualTo(204);

    UserAccount b = users.findById(userB.getId()).orElseThrow();
    assertThat(b.isPhoneVerified()).isTrue();
    assertThat(b.getPhone()).isEqualTo(FREE);
  }

  private String lastSmsCode() {
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(notifications, org.mockito.Mockito.atLeastOnce()).sendSms(anyString(), text.capture());
    return text.getValue().replaceAll("\\D+(\\d{6})\\D.*", "$1");
  }

  private MockHttpServletResponse addPhone(UserAccount user, String phone) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/me/phone")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse verifyPhone(UserAccount user, String code) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/me/phone/verify")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", code))))
        .andReturn()
        .getResponse();
  }

  private String accessToken(UserAccount user) throws Exception {
    MockHttpServletResponse login =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new LoginRequest(user.getEmail(), PASSWORD))))
            .andReturn()
            .getResponse();
    return (String) json(login).get("accessToken");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> json(MockHttpServletResponse response) throws Exception {
    return objectMapper.readValue(response.getContentAsString(), Map.class);
  }

  /** Response headers minus the ones that legitimately differ per request. */
  private static Map<String, java.util.List<String>> comparableHeaders(
      MockHttpServletResponse response) {
    Map<String, java.util.List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (String name : response.getHeaderNames()) {
      String lower = name.toLowerCase();
      if (lower.equals("date") || lower.contains("correlation") || lower.contains("trace")) {
        continue;
      }
      headers.put(name, response.getHeaders(name));
    }
    return headers;
  }
}
