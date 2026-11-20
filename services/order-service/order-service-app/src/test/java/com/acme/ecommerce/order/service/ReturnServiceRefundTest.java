package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.domain.ReturnStatus;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.order.service.OrderStore.ReturnOutcome;
import com.acme.ecommerce.order.service.OrderStore.ReturnRefundPlan;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O-05a: the refund flow across wallet leg, rest leg, retries, PENDING and COD (CTO C1, C3, C5).
 */
class ReturnServiceRefundTest {

  private final OrderStore store = mock(OrderStore.class);
  private final OrderRemote remote = mock(OrderRemote.class);
  private final OrderEventPublisher events = mock(OrderEventPublisher.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final ReturnService service = new ReturnService(store, remote, events, tx);

  private final UUID id = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  private static ReturnResponse response() {
    return new ReturnResponse(
        null,
        null,
        "REFUNDED",
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        null);
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    when(tx.execute(any()))
        .thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
    when(store.claimRefundLease(id)).thenReturn(true);
    when(store.completeRefund(id)).thenReturn(new ReturnOutcome(response(), null));
  }

  private ReturnRefundPlan plan(
      ReturnStatus status,
      String wallet,
      String rest,
      String route,
      boolean walletDone,
      String restState,
      int attempt) {
    return new ReturnRefundPlan(
        id,
        orderId,
        userId,
        "INR",
        status,
        List.of(),
        new BigDecimal(wallet),
        new BigDecimal(rest),
        route,
        walletDone,
        restState,
        attempt,
        "return-" + id + (attempt == 0 ? "" : "-r" + attempt));
  }

  private void given(ReturnRefundPlan p) {
    when(store.refundPlan(id)).thenReturn(p);
    when(store.freezeLegs(id)).thenReturn(p);
  }

  private ReturnRefundPlan split() {
    return plan(ReturnStatus.PICKED_UP, "30.00", "70.00", "PAYMENT_SOURCE", false, null, 0);
  }

  private void verifyFinalizedOnce() {
    verify(store, times(1)).completeRefund(id);
    verify(remote, times(1)).releaseStock(eq(orderId), eq(id), any());
    verify(events, times(1)).publishReturn(eq(OrderEventType.RETURN_REFUNDED), any(), any());
  }

  private void verifyNotFinalized() {
    verify(store, never()).completeRefund(any());
    verify(remote, never()).releaseStock(any(), any(), any());
    verify(events, never()).publishReturn(any(), any(), any());
  }

  @Test
  @DisplayName("AC-6: the wallet-done write precedes the card refund call")
  void walletDoneBeforeRefund() {
    given(split());
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("SUCCEEDED");

    service.refund(id);

    InOrder order = inOrder(remote, store);
    order
        .verify(remote)
        .creditWallet(userId, new BigDecimal("30.00"), "INR", orderId, "return-wallet-" + id);
    order.verify(store).markWalletLegDone(id);
    order
        .verify(remote)
        .refund(orderId, new BigDecimal("70.00"), "Return refunded", "return-" + id, null);
    order.verify(store).setRestLegState(id, "SUCCEEDED");
    verifyFinalizedOnce();
    verify(store).releaseRefundLease(id);
  }

  @Test
  @DisplayName(
      "AC-7 and C3: a card exception records FAILED, never bumps the attempt, stays PICKED_UP")
  void cardThrows() {
    given(split());
    when(remote.refund(any(), any(), anyString(), anyString(), any()))
        .thenThrow(new CodedException(ErrorCode.DEPENDENCY_UNAVAILABLE, "down"));

    assertThatThrownBy(() -> service.refund(id)).isInstanceOf(CodedException.class);

    verify(store).markWalletLegDone(id);
    verify(store).setRestLegState(id, "FAILED");
    verify(store, never()).bumpRestLegAttempt(any(), anyInt());
    verify(remote, never()).refund(any(), any(), any(), eq("return-" + id + "-r1"), any());
    verifyNotFinalized();
    verify(store).releaseRefundLease(id);
  }

  @Test
  @DisplayName(
      "AC-8: a retry after wallet-ok card-failed skips the wallet, keeps the key, finalizes")
  void retryAfterCardFailure() {
    given(plan(ReturnStatus.PICKED_UP, "30.00", "70.00", "PAYMENT_SOURCE", true, "FAILED", 0));
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("SUCCEEDED");

    service.refund(id);

    verify(remote, never()).creditWallet(any(), any(), any(), any(), anyString());
    verify(remote)
        .refund(orderId, new BigDecimal("70.00"), "Return refunded", "return-" + id, null);
    verify(store, never()).bumpRestLegAttempt(any(), anyInt());
    verifyFinalizedOnce();
  }

  @Test
  @DisplayName("AC-9: a REFUNDED return makes no remote call and no write")
  void alreadyRefunded() {
    when(store.refundPlan(id))
        .thenReturn(
            plan(ReturnStatus.REFUNDED, "30.00", "70.00", "PAYMENT_SOURCE", true, "SUCCEEDED", 0));
    when(store.view(id)).thenReturn(response());

    assertThat(service.refund(id)).isEqualTo(response());

    verifyNoInteractions(remote, events);
    verify(store, never()).claimRefundLease(any());
    verify(store, never()).completeRefund(any());
  }

  @Test
  @DisplayName("AC-11: a PENDING card leg is recorded; no finalize, stock release or event")
  void pendingLeg() {
    given(split());
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("PENDING");

    service.refund(id);

    verify(store).setRestLegState(id, "PENDING");
    verifyNotFinalized();
  }

  @Test
  @DisplayName("AC-13: a retry while PENDING reuses the same key")
  void retryWhilePending() {
    given(plan(ReturnStatus.PICKED_UP, "0.00", "70.00", "PAYMENT_SOURCE", false, "PENDING", 0));
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("PENDING");

    service.refund(id);

    verify(remote)
        .refund(orderId, new BigDecimal("70.00"), "Return refunded", "return-" + id, null);
    verify(store, never()).bumpRestLegAttempt(any(), anyInt());
    verifyNotFinalized();
  }

  @Test
  @DisplayName("C3: only a FAILED refund row starts the -r1 key, once, then finalizes")
  void failedRowStartsNewKey() {
    given(plan(ReturnStatus.PICKED_UP, "0.00", "70.00", "PAYMENT_SOURCE", true, "PENDING", 0));
    when(remote.refund(any(), any(), anyString(), eq("return-" + id), any())).thenReturn("FAILED");
    when(remote.refund(any(), any(), anyString(), eq("return-" + id + "-r1"), any()))
        .thenReturn("SUCCEEDED");
    when(store.bumpRestLegAttempt(id, 0)).thenReturn(true);

    service.refund(id);

    InOrder order = inOrder(remote, store);
    order.verify(remote).refund(any(), any(), anyString(), eq("return-" + id), any());
    order.verify(store).bumpRestLegAttempt(id, 0);
    order.verify(remote).refund(any(), any(), anyString(), eq("return-" + id + "-r1"), any());
    verifyFinalizedOnce();
  }

  @Test
  @DisplayName("a lost attempt-bump race is a 409 and records nothing")
  void bumpRace() {
    given(plan(ReturnStatus.PICKED_UP, "0.00", "70.00", "PAYMENT_SOURCE", true, "FAILED", 0));
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("FAILED");
    when(store.bumpRestLegAttempt(id, 0)).thenReturn(false);

    assertThatThrownBy(() -> service.refund(id))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

    verify(store, never()).setRestLegState(any(), anyString());
    verifyNotFinalized();
  }

  @Test
  @DisplayName("AC-4: COD sends destination WALLET once and credits no wallet itself")
  void codDestinationWallet() {
    given(plan(ReturnStatus.PICKED_UP, "0.00", "100.00", "PAYMENT_WALLET", false, null, 0));
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("SUCCEEDED");

    service.refund(id);

    verify(remote, times(1))
        .refund(orderId, new BigDecimal("100.00"), "Return refunded", "return-" + id, "WALLET");
    verify(remote, never()).creditWallet(any(), any(), any(), any(), anyString());
    verifyFinalizedOnce();
  }

  @Test
  @DisplayName("C1 and AC-5: a 409 or 404 on a COD refund credits nothing and leaves PICKED_UP")
  void codRejected() {
    given(plan(ReturnStatus.PICKED_UP, "0.00", "100.00", "PAYMENT_WALLET", false, null, 0));
    for (ErrorCode code : List.of(ErrorCode.RESOURCE_CONFLICT, ErrorCode.PAYMENT_NOT_FOUND)) {
      doThrow(new CodedException(code, "rejected"))
          .when(remote)
          .refund(any(), any(), anyString(), anyString(), any());

      assertThatThrownBy(() -> service.refund(id))
          .isInstanceOfSatisfying(
              CodedException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    verify(remote, never()).creditWallet(any(), any(), any(), any(), anyString());
    verifyNotFinalized();
    verify(store, times(2)).setRestLegState(id, "FAILED");
  }

  @Test
  @DisplayName("AC-5: payment-service down is 503 and nothing is finalized")
  void codUnavailable() {
    given(plan(ReturnStatus.PICKED_UP, "0.00", "100.00", "PAYMENT_WALLET", false, null, 0));
    when(remote.refund(any(), any(), anyString(), anyString(), any()))
        .thenThrow(new CodedException(ErrorCode.DEPENDENCY_UNAVAILABLE, "down"));

    assertThatThrownBy(() -> service.refund(id))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));

    verifyNotFinalized();
  }

  @Test
  @DisplayName("a zero refund makes no payment or wallet call and still completes")
  void zeroRefund() {
    given(plan(ReturnStatus.PICKED_UP, "0.00", "0.00", "NONE", false, null, 0));

    service.refund(id);

    verify(remote, never()).refund(any(), any(), any(), any(), any());
    verify(remote, never()).creditWallet(any(), any(), any(), any(), anyString());
    verifyFinalizedOnce();
  }

  @Test
  @DisplayName("a wallet-method remainder is credited under return-rest-<id>")
  void directWallet() {
    given(plan(ReturnStatus.PICKED_UP, "0.00", "50.00", "DIRECT_WALLET", false, null, 0));

    service.refund(id);

    verify(remote)
        .creditWallet(userId, new BigDecimal("50.00"), "INR", orderId, "return-rest-" + id);
    verify(store).setRestLegState(id, "SUCCEEDED");
    verifyFinalizedOnce();
  }

  @Test
  @DisplayName("a second caller while the lease is held gets 409 and touches nothing")
  void refundInProgress() {
    when(store.refundPlan(id)).thenReturn(split());
    when(store.claimRefundLease(id)).thenReturn(false);

    assertThatThrownBy(() -> service.refund(id))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

    verifyNoInteractions(remote, events);
    verify(store, never()).releaseRefundLease(any());
  }

  // ---- resumePending (watch) ------------------------------------------------------------

  private ReturnRefundPlan pending() {
    return plan(ReturnStatus.PICKED_UP, "0.00", "70.00", "PAYMENT_SOURCE", true, "PENDING", 0);
  }

  @Test
  @DisplayName("AC-12: a PENDING leg that settled SUCCEEDED finalizes once")
  void resumeSucceeded() {
    when(store.refundPlan(id)).thenReturn(pending());
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("SUCCEEDED");

    service.resumePending(id);

    verify(remote)
        .refund(orderId, new BigDecimal("70.00"), "Return refunded", "return-" + id, null);
    verify(store).setRestLegState(id, "SUCCEEDED");
    verifyFinalizedOnce();
    verify(store).releaseRefundLease(id);
  }

  @Test
  @DisplayName("AC-12: a leg that settled FAILED is recorded and the return stays PICKED_UP")
  void resumeFailed() {
    when(store.refundPlan(id)).thenReturn(pending());
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("FAILED");

    service.resumePending(id);

    verify(store).setRestLegState(id, "FAILED");
    verify(store, never()).bumpRestLegAttempt(any(), anyInt());
    verifyNotFinalized();
  }

  @Test
  @DisplayName("a leg still PENDING is left alone")
  void resumeStillPending() {
    when(store.refundPlan(id)).thenReturn(pending());
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("PENDING");

    service.resumePending(id);

    verify(store, never()).setRestLegState(any(), anyString());
    verifyNotFinalized();
  }

  @Test
  @DisplayName("resumePending does nothing without the lease")
  void resumeWithoutLease() {
    when(store.claimRefundLease(id)).thenReturn(false);

    service.resumePending(id);

    verifyNoInteractions(remote, events);
    verify(store, never()).releaseRefundLease(any());
  }

  @Test
  @DisplayName("COD destination is sent as WALLET on a resume as well")
  void resumeKeepsDestination() {
    when(store.refundPlan(id))
        .thenReturn(
            plan(ReturnStatus.PICKED_UP, "0.00", "100.00", "PAYMENT_WALLET", false, "PENDING", 2));
    when(remote.refund(any(), any(), anyString(), anyString(), any())).thenReturn("PENDING");

    service.resumePending(id);

    verify(remote)
        .refund(
            orderId, new BigDecimal("100.00"), "Return refunded", "return-" + id + "-r2", "WALLET");
    verify(remote, never()).refund(any(), any(), any(), any(), isNull());
  }
}
