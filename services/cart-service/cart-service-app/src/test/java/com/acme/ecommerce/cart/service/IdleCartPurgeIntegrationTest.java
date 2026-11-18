package com.acme.ecommerce.cart.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.cart.support.AbstractCartIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** TD-BE-11: carts idle beyond the window are deleted, recent ones are kept. */
class IdleCartPurgeIntegrationTest extends AbstractCartIntegrationTest {

  @Autowired private IdleCartPurge purge;
  @Autowired private JdbcClient jdbc;

  private UUID cart(int ageDays) {
    UUID user = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO cart.carts (user_id, updated_at)"
                + " VALUES (:u, now() - make_interval(days => :d))")
        .param("u", user)
        .param("d", ageDays)
        .update();
    return user;
  }

  private boolean exists(UUID user) {
    return jdbc.sql("SELECT count(*) FROM cart.carts WHERE user_id = :u")
            .param("u", user)
            .query(Long.class)
            .single()
        > 0;
  }

  @Test
  @DisplayName("deletes carts idle longer than the window and keeps recent ones")
  void purgesOnlyIdleCarts() {
    UUID old = cart(400);
    UUID recent = cart(3);

    assertThat(purge.purge()).isGreaterThanOrEqualTo(1);

    assertThat(exists(old)).isFalse();
    assertThat(exists(recent)).isTrue();
  }
}
