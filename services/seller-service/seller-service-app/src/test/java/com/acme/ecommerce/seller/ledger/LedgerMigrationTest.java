package com.acme.ecommerce.seller.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.seller.support.AbstractSellerIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V3: FEE_REVERSAL is a valid entry type and a discrepancy is unique per (shipment, kind, ref). */
class LedgerMigrationTest extends AbstractSellerIntegrationTest {

  @Autowired private JdbcClient jdbc;

  private UUID seller() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO sellers (id, user_id, display_name, slug, status, commission_percent)"
                + " VALUES (:i, :u, 'M', :s, 'APPROVED', 10)")
        .param("i", id)
        .param("u", UUID.randomUUID())
        .param("s", "m-" + id)
        .update();
    return id;
  }

  @Test
  @DisplayName("a FEE_REVERSAL ledger row is accepted")
  void feeReversalTypeAccepted() {
    UUID s = seller();
    int n =
        jdbc.sql(
                "INSERT INTO ledger_entries (seller_id, type, amount, currency, order_id, shipment_id)"
                    + " VALUES (:s, 'FEE_REVERSAL', :a, 'INR', :o, :sh)")
            .param("s", s)
            .param("a", new BigDecimal("10.00"))
            .param("o", UUID.randomUUID())
            .param("sh", UUID.randomUUID())
            .update();
    assertThat(n).isEqualTo(1);
  }

  @Test
  @DisplayName("a second discrepancy with the same shipment, kind and ref (even null) is rejected")
  void duplicateDiscrepancyRejected() {
    UUID shipment = UUID.randomUUID();
    String sql =
        "INSERT INTO ledger_discrepancies (shipment_id, kind, ref) VALUES (:sh, 'MISSING_SALE', :r)";
    jdbc.sql(sql).param("sh", shipment).param("r", null, java.sql.Types.OTHER).update();
    assertThatThrownBy(
            () ->
                jdbc.sql(sql).param("sh", shipment).param("r", null, java.sql.Types.OTHER).update())
        .isInstanceOf(DuplicateKeyException.class);
  }
}
