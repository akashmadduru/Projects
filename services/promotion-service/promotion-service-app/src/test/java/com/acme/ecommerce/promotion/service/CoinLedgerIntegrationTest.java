package com.acme.ecommerce.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.promotion.client.UserFeignClient;
import com.acme.ecommerce.promotion.support.AbstractPromotionIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Coins ledger on a real database (TD-P8-01): earn, redeem, release, refund clawback, expiry. */
class CoinLedgerIntegrationTest extends AbstractPromotionIntegrationTest {

  @MockitoBean private MembershipLookup members;
  @MockitoBean private UserFeignClient users;

  @Autowired private CoinService coins;
  @Autowired private JdbcClient jdbc;

  private UUID earned(int rupees) {
    UUID user = UUID.randomUUID();
    when(members.isMember(user)).thenReturn(false);
    when(users.referralByReferee(any())).thenReturn(null);
    coins.onDelivered(UUID.randomUUID(), user, new BigDecimal(rupees), "INR");
    return user;
  }

  @Test
  @DisplayName("a delivered order earns coins once, even when the event is replayed")
  void earnIsIdempotent() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    when(members.isMember(user)).thenReturn(false);

    coins.onDelivered(order, user, new BigDecimal("1000"), "INR");
    int once = coins.balance(user);
    coins.onDelivered(order, user, new BigDecimal("1000"), "INR");

    assertThat(once).isPositive();
    assertThat(coins.balance(user)).isEqualTo(once);
  }

  @Test
  @DisplayName("members earn twice the coins")
  void membersEarnDouble() {
    UUID plain = earned(1000);
    UUID member = UUID.randomUUID();
    when(members.isMember(member)).thenReturn(true);
    coins.onDelivered(UUID.randomUUID(), member, new BigDecimal("1000"), "INR");

    assertThat(coins.balance(member)).isEqualTo(2 * coins.balance(plain));
  }

  @Test
  @DisplayName(
      "redeem is idempotent per order, refuses overspend, and release gives coins back once")
  void redeemAndRelease() {
    UUID user = earned(5000);
    int start = coins.balance(user);
    UUID order = UUID.randomUUID();

    coins.redeem(user, 10, order);
    coins.redeem(user, 10, order);
    assertThat(coins.balance(user)).isEqualTo(start - 10);
    assertThatThrownBy(() -> coins.redeem(user, start, UUID.randomUUID()))
        .satisfies(
            e ->
                assertThat(((CodedException) e).errorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

    coins.releaseRedeem(order);
    coins.releaseRedeem(order);
    coins.releaseRedeem(UUID.randomUUID()); // nothing redeemed: no-op
    assertThat(coins.balance(user)).isEqualTo(start);
  }

  @Test
  @DisplayName("a full refund takes the earned coins back, never below zero")
  void refundClawsBack() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    when(members.isMember(user)).thenReturn(false);
    coins.onDelivered(order, user, new BigDecimal("2000"), "INR");
    int earned = coins.balance(user);
    coins.redeem(user, earned - 1, UUID.randomUUID()); // spend most of it first

    coins.onFullyRefunded(order, user);
    coins.onFullyRefunded(order, user);

    assertThat(coins.balance(user)).isZero();
  }

  @Test
  @DisplayName("coins expiring within 30 days are reported as expiring soon")
  void expiringSoon() {
    UUID user = earned(1000);
    int earned = coins.balance(user);
    jdbc.sql(
            "UPDATE promotion.coin_ledger SET expires_at = now() + interval '10 days'"
                + " WHERE user_id = :u AND reason = 'EARN_ORDER'")
        .param("u", user)
        .update();

    assertThat(coins.view(user, "INR").expiringSoon()).isEqualTo(earned);
  }
}
