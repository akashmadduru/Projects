package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.payment.client.NotificationFeignClient;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** TD-P8-06: hashed codes, redeem lockout. The SQL itself is exercised against Postgres in CI. */
class GiftCardServiceTest {

  private final UUID user = UUID.randomUUID();
  private JdbcClient jdbc;
  private TransactionTemplate tx;
  private GiftCardService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(
            inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    service =
        new GiftCardService(
            jdbc,
            mock(WalletService.class),
            mock(NotificationFeignClient.class),
            tx,
            mock(JobLock.class));
  }

  private void stubRecentFailures(int count) {
    when(jdbc.sql(startsWith("SELECT count(*)"))
            .param(anyString(), any())
            .query(Integer.class)
            .single())
        .thenReturn(count);
  }

  private static ErrorCode codeOf(Throwable e) {
    return ((CodedException) e).errorCode();
  }

  @Test
  @DisplayName("codes are accepted with or without dashes, spaces and in any case")
  void canonicalForm() {
    assertThat(GiftCardService.canonical("abcd-efgh-jklm-npqr")).isEqualTo("ABCD-EFGH-JKLM-NPQR");
    assertThat(GiftCardService.canonical(" ABCDEFGHJKLMNPQR ")).isEqualTo("ABCD-EFGH-JKLM-NPQR");
    assertThat(GiftCardService.canonical("abcd efgh jklm npqr")).isEqualTo("ABCD-EFGH-JKLM-NPQR");
    assertThat(GiftCardService.canonical("ABCD-EFGH")).isNull();
    assertThat(GiftCardService.canonical(null)).isNull();
  }

  @Test
  @DisplayName("hash matches the V4 backfill: SHA-256 hex of the dashed, upper-case code")
  void hashMatchesMigration() {
    // encode(sha256(convert_to('ABCD-EFGH-JKLM-NPQR', 'UTF8')), 'hex') in Postgres.
    assertThat(GiftCardService.hash("ABCD-EFGH-JKLM-NPQR"))
        .isEqualTo("2356329028e8ba9d57b3f7b33fef1b1f6d35dc228c095c4959bb873738fc2f3c");
  }

  @Test
  @DisplayName("a locked-out user is refused before any code lookup, even with a valid code")
  void lockedOutUserIsRefused() {
    stubRecentFailures(GiftCardService.MAX_FAILED_REDEEMS);

    assertThatThrownBy(() -> service.redeem(user, "ABCD-EFGH-JKLM-NPQR"))
        .isInstanceOf(CodedException.class)
        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.RATE_LIMITED));
    verify(tx, never()).execute(any());
  }

  @Test
  @DisplayName("an unknown code is 422 and is counted as a failed attempt")
  @SuppressWarnings("unchecked")
  void unknownCodeIsCounted() {
    stubRecentFailures(0);
    when(jdbc.sql(contains("WHERE code_hash = :h"))
            .param(anyString(), any())
            .query(any(RowMapper.class))
            .optional())
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.redeem(user, "ABCD-EFGH-JKLM-NPQR"))
        .isInstanceOf(CodedException.class)
        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.GIFT_CARD_INVALID));
    verify(jdbc).sql(startsWith("INSERT INTO payment.gift_card_redeem_failures"));
  }

  @Test
  @DisplayName("a malformed code is 422 without touching the database lookup")
  void malformedCodeIsInvalid() {
    stubRecentFailures(0);

    assertThatThrownBy(() -> service.redeem(user, "nope"))
        .isInstanceOf(CodedException.class)
        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.GIFT_CARD_INVALID));
    verify(jdbc, never()).sql(contains("WHERE code_hash = :h"));
  }
}
