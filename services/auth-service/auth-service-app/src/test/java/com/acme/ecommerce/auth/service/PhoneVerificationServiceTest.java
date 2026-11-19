package com.acme.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.error.AuthException;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.service.OneTimeTokenService.Purpose;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

class PhoneVerificationServiceTest {

  private static final String PHONE = "+919876500001";

  private final UserAccountRepository users = mock(UserAccountRepository.class);
  private final OneTimeTokenService tokens = mock(OneTimeTokenService.class);
  private final NotificationGateway notifications = mock(NotificationGateway.class);
  private final PhoneClaimNotifier claimNotifier = mock(PhoneClaimNotifier.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final PhoneVerificationService service =
      new PhoneVerificationService(users, tokens, notifications, claimNotifier, tx);

  private final UUID userId = UUID.randomUUID();
  private final UserAccount user = UserAccount.create("a@example.com", "A", true);

  @BeforeEach
  void setUp() {
    user.changePhone(PHONE);
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(tokens.verifyCode(eq(userId), eq(Purpose.PHONE_VERIFY), eq(PHONE), anyString(), anyInt()))
        .thenReturn(true);
  }

  @Test
  void concurrentUniqueIndexViolationIsReportedAsAnInvalidCode() {
    doThrow(new DataIntegrityViolationException("users_phone_verified_key"))
        .when(tx)
        .executeWithoutResult(any());

    assertThatThrownBy(() -> service.verifyPhone(userId, "123456"))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> assertThat(e.authErrorCode()).isEqualTo(AuthErrorCode.AUTH_OTP_INVALID));
  }

  @Test
  void recheckFindingTheNumberVerifiedElsewhereIsReportedAsAnInvalidCode() {
    when(users.existsByPhoneAndPhoneVerifiedTrueAndIdNot(PHONE, userId)).thenReturn(true);
    doAnswer(
            inv -> {
              inv.<Consumer<Object>>getArgument(0).accept(null);
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());

    assertThatThrownBy(() -> service.verifyPhone(userId, "123456"))
        .isInstanceOfSatisfying(
            AuthException.class,
            e -> assertThat(e.authErrorCode()).isEqualTo(AuthErrorCode.AUTH_OTP_INVALID));
    assertThat(user.isPhoneVerified()).isFalse();
  }

  @Test
  void takenNumberSendsNoSmsAndNotifiesTheOwner() {
    when(users.existsByPhoneAndPhoneVerifiedTrueAndIdNot(PHONE, userId)).thenReturn(true);
    when(tx.execute(any())).thenReturn(false);
    when(tokens.issueCode(eq(userId), eq(Purpose.PHONE_VERIFY), eq(PHONE), any(), any()))
        .thenReturn(Optional.of("654321"));

    service.addPhone(userId, PHONE);

    verify(notifications, never()).sendSms(anyString(), anyString());
    verify(claimNotifier).attempted(PHONE);
  }

  @Test
  void freeNumberSendsTheSmsAndNoOwnerNotice() {
    when(tx.execute(any())).thenReturn(false);
    when(tokens.issueCode(eq(userId), eq(Purpose.PHONE_VERIFY), eq(PHONE), any(), any()))
        .thenReturn(Optional.of("654321"));

    service.addPhone(userId, PHONE);

    verify(notifications).sendSms(eq(PHONE), anyString());
    verify(claimNotifier, never()).attempted(anyString());
  }
}
