package com.acme.ecommerce.user.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** AC-9: V6 adds users.outbox with its three partial indexes and nothing destructive. */
class OutboxMigrationIT extends AbstractUserIntegrationTest {

  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("Flyway is at version 6 with users.outbox and its 3 indexes")
  void tableAndIndexesExist() {
    Integer max =
        jdbc.sql(
                "SELECT max(version::int) FROM users.flyway_schema_history WHERE success"
                    + " AND version IS NOT NULL")
            .query(Integer.class)
            .single();
    assertThat(max).isEqualTo(6);
    assertThat(
            jdbc.sql("SELECT to_regclass('users.outbox') IS NOT NULL")
                .query(Boolean.class)
                .single())
        .isTrue();
    List<String> indexes =
        jdbc.sql(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'users' AND tablename = 'outbox'")
            .query(String.class)
            .list();
    assertThat(indexes)
        .contains("ix_user_outbox_due", "ix_user_outbox_aggregate_pending", "ix_user_outbox_dead");
  }

  @Test
  @DisplayName("V6 is additive: no ALTER and no DROP")
  void additiveOnly() throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/db/migration/V6__outbox.sql")) {
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
      assertThat(sql).doesNotContain("ALTER ").doesNotContain("DROP ");
    }
  }
}
