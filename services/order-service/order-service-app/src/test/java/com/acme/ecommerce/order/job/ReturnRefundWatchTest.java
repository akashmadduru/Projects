package com.acme.ecommerce.order.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.order.service.OrderStore;
import com.acme.ecommerce.order.service.ReturnService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReturnRefundWatchTest {

  private final OrderStore store = mock(OrderStore.class);
  private final ReturnService returns = mock(ReturnService.class);
  private final JobLock lock = mock(JobLock.class);
  private final ReturnRefundWatch watch = new ReturnRefundWatch(store, returns, lock);

  private void runUnderLock() {
    ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
    when(lock.runExclusive(eq("order-return-refund-watch"), job.capture()))
        .thenAnswer(
            i -> {
              job.getValue().run();
              return true;
            });
    watch.poll();
  }

  @Test
  @DisplayName("every pending return is resumed; one failing does not stop the rest")
  void resumesAll() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(store.pendingRestLegIds()).thenReturn(List.of(a, b));
    doThrow(new IllegalStateException("boom")).when(returns).resumePending(a);

    runUnderLock();

    verify(returns).resumePending(a);
    verify(returns).resumePending(b);
  }

  @Test
  @DisplayName("when the job lock is held elsewhere nothing is processed")
  void lockHeld() {
    when(lock.runExclusive(eq("order-return-refund-watch"), any())).thenReturn(false);

    watch.poll();

    verifyNoInteractions(store, returns);
    verify(returns, never()).resumePending(any());
  }
}
