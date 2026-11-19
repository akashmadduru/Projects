package com.acme.ecommerce.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.repository.UserCredentialRepository;
import com.acme.ecommerce.auth.service.NotificationGateway;
import com.acme.ecommerce.auth.service.OneTimeTokenService;
import com.acme.ecommerce.auth.service.OneTimeTokenService.Purpose;
import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.acme.ecommerce.auth.support.DatabaseCleaner;
import com.acme.ecommerce.auth.support.UserFixtures;
import com.acme.ecommerce.common.security.Roles;
import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** AC-12 (b): a reset that creates a credential is documented and notified after commit. */
class PasswordResetIntegrationTest extends AbstractAuthIntegrationTest {

  private static final String NEW_PASSWORD = "a-brand-new-password";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserFixtures userFixtures;
  @Autowired private DatabaseCleaner databaseCleaner;
  @Autowired private OneTimeTokenService tokens;
  @Autowired private UserCredentialRepository credentials;
  @MockitoBean private NotificationGateway notifications;

  @BeforeEach
  void setUp() {
    databaseCleaner.clean();
    reset(notifications);
  }

  @Test
  @DisplayName("Google-only user: 204, credential row created, PASSWORD_SET sent after commit")
  void googleOnlyUserGetsACredentialAndANotice() throws Exception {
    UserAccount user = userFixtures.createSocialOnlyUser("g@example.com", Roles.ROLE_VIEWER);
    AtomicBoolean rowVisibleAtSend = new AtomicBoolean();
    doAnswer(
            inv -> {
              rowVisibleAtSend.set(credentials.existsById(user.getId()));
              return null;
            })
        .when(notifications)
        .sendEmail(eq("g@example.com"), eq(EmailTemplate.PASSWORD_SET), anyMap());

    assertThat(resetPassword(user).getStatus()).isEqualTo(204);

    assertThat(credentials.existsById(user.getId())).isTrue();
    verify(notifications, times(1))
        .sendEmail(
            eq("g@example.com"),
            eq(EmailTemplate.PASSWORD_SET),
            eq(
                Map.of(
                    "name", "Test g@example.com",
                    "link", "http://localhost:3000/account/security")));
    assertThat(rowVisibleAtSend).isTrue();
  }

  @Test
  @DisplayName("password user: 204 and no PASSWORD_SET")
  void passwordUserGetsNoNotice() throws Exception {
    UserAccount user =
        userFixtures.createPasswordUser("p@example.com", "old-password-123", Roles.ROLE_VIEWER);

    assertThat(resetPassword(user).getStatus()).isEqualTo(204);

    verify(notifications, never()).sendEmail(any(), eq(EmailTemplate.PASSWORD_SET), anyMap());
  }

  @Test
  @DisplayName("a failing gateway does not fail the reset")
  void failingGatewayDoesNotFailTheReset() throws Exception {
    UserAccount user = userFixtures.createSocialOnlyUser("f@example.com", Roles.ROLE_VIEWER);
    doThrow(new IllegalStateException("boom"))
        .when(notifications)
        .sendEmail(any(), any(), anyMap());

    assertThat(resetPassword(user).getStatus()).isEqualTo(204);

    assertThat(credentials.existsById(user.getId())).isTrue();
  }

  private org.springframework.mock.web.MockHttpServletResponse resetPassword(UserAccount user)
      throws Exception {
    String token =
        tokens.issueLinkToken(user.getId(), Purpose.PASSWORD_RESET, Duration.ofMinutes(5));
    return mockMvc
        .perform(
            post("/api/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("token", token, "newPassword", NEW_PASSWORD))))
        .andReturn()
        .getResponse();
  }
}
