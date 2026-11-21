package com.acme.ecommerce.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import com.acme.ecommerce.order.service.OrderRemote;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Relay tolerance of rows it does not understand (rollback safety for newer outbox kinds). */
class OutboxRelayTest extends AbstractOrderIntegrationTest {

  @MockitoBean private OrderRemote remote;
  @MockitoBean private OrderEventPublisher events;

  @Autowired private Outbox outbox;
  @Autowired private OutboxRelay relay;
  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("an unknown kind is skipped and left PENDING; a valid row behind it is delivered")
  void unknownKindDoesNotStallThePass() {
    UUID bogusAggregate = UUID.randomUUID();
    UUID validAggregate = UUID.randomUUID();
    outbox.enqueue(Kind.RESTORE_STOCK, bogusAggregate);
    jdbc.update("update orders.outbox set kind = 'BOGUS' where aggregate_id = ?", bogusAggregate);
    outbox.enqueue(Kind.RESTORE_STOCK, validAggregate);

    // Other test classes share this database, so rows of theirs may be delivered in the same pass.
    relay.relay();

    verify(remote).restoreStockNow(validAggregate);
    assertThat(
            jdbc.queryForObject(
                "select status from orders.outbox where aggregate_id = ?",
                String.class,
                bogusAggregate))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select status from orders.outbox where aggregate_id = ?",
                String.class,
                validAggregate))
        .isEqualTo("DONE");
  }
}
