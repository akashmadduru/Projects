package com.acme.ecommerce.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.promotion.client.UserFeignClient;
import com.acme.ecommerce.promotion.event.OrderEvent;
import com.acme.ecommerce.promotion.event.OrderEventListener;
import com.acme.ecommerce.promotion.support.AbstractPromotionIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Per-return coin reversal (O-05b, AC-14..AC-18) on a real database. */
class CoinReturnReversalIntegrationTest extends AbstractPromotionIntegrationTest {

  @MockitoBean private MembershipLookup members;
  @MockitoBean private UserFeignClient users;

  @Autowired private CoinService coins;
  @Autowired private JdbcClient jdbc;
  @Autowired private OrderEventListener listener;

  private static final BigDecimal ORDER_VALUE = new BigDecimal("300");
  private static final BigDecimal THIRD = new BigDecimal("100");

  private void ledger(UUID user, int delta, String reason, String ref) {
    jdbc.sql(
            "INSERT INTO promotion.coin_ledger (user_id, delta, reason, reference_id)"
                + " VALUES (:u, :d, :r, :ref)")
        .param("u", user)
        .param("d", delta)
        .param("r", reason)
        .param("ref", ref)
        .update();
  }

  /** Seeds an order with n redeemed and e earned coins plus spare balance. */
  private void seed(UUID user, UUID order, int n, int e, int spare) {
    if (e > 0) {
      ledger(user, e, "EARN_ORDER", order.toString());
    }
    if (n > 0) {
      ledger(user, -n, "REDEEM_ORDER", order.toString());
    }
    if (spare > 0) {
      ledger(user, spare, "ADJUST", "spare-" + UUID.randomUUID());
    }
  }

  private int sum(UUID user, String refPrefix) {
    return jdbc.sql(
            "SELECT COALESCE(SUM(delta), 0) FROM promotion.coin_ledger"
                + " WHERE user_id = :u AND reference_id LIKE :p")
        .param("u", user)
        .param("p", refPrefix + "%")
        .query(Integer.class)
        .single();
  }

  private int rows(UUID user, String refLike) {
    return jdbc.sql(
            "SELECT count(*) FROM promotion.coin_ledger WHERE user_id = :u AND reference_id LIKE :p")
        .param("u", user)
        .param("p", refLike)
        .query(Integer.class)
        .single();
  }

  private int reversalRows(UUID order) {
    return jdbc.sql("SELECT count(*) FROM promotion.coin_return_reversals WHERE order_id = :o")
        .param("o", order)
        .query(Integer.class)
        .single();
  }

  private Map<String, Object> reversal(UUID returnId) {
    return jdbc.sql(
            "SELECT redeem_due, earn_due, earn_taken, completes"
                + " FROM promotion.coin_return_reversals WHERE return_id = :r")
        .param("r", returnId)
        .query()
        .singleRow();
  }

  @Test
  @DisplayName("a partial return gives back and reverses floor shares, linked to the return")
  void partialFloors() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    UUID ret = UUID.randomUUID();
    seed(user, order, 25, 33, 100);

    coins.onReturnRefunded(order, user, ret, THIRD, ORDER_VALUE, false);

    assertThat(sum(user, "return-redeem-" + ret)).isEqualTo(8); // floor(25/3)
    assertThat(sum(user, "return-earn-" + ret)).isEqualTo(-11); // floor(33/3)
    Map<String, Object> r = reversal(ret);
    assertThat(((Number) r.get("redeem_due")).intValue()).isEqualTo(8);
    assertThat(((Number) r.get("earn_due")).intValue()).isEqualTo(11);
    assertThat(((Number) r.get("earn_taken")).intValue()).isEqualTo(11);
    assertThat(r.get("completes")).isEqualTo(false);
  }

  @Test
  @DisplayName("replaying the same return writes no new ledger or reversal row")
  void replayIsNoop() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    UUID ret = UUID.randomUUID();
    seed(user, order, 25, 33, 100);

    coins.onReturnRefunded(order, user, ret, THIRD, ORDER_VALUE, false);
    int balance = coins.balance(user);
    coins.onReturnRefunded(order, user, ret, THIRD, ORDER_VALUE, false);

    assertThat(coins.balance(user)).isEqualTo(balance);
    assertThat(rows(user, "return-%-" + ret)).isEqualTo(2);
    assertThat(reversalRows(order)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "spent coins: balance goes to 0 never below, shortfall recorded, replay writes nothing")
  void spentCoinsClampAndReplayAfterClamp() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    UUID ret = UUID.randomUUID();
    ledger(user, 40, "EARN_ORDER", order.toString());
    ledger(user, -35, "REDEEM_ORDER", UUID.randomUUID().toString()); // spent elsewhere; balance 5

    coins.onReturnRefunded(order, user, ret, ORDER_VALUE, ORDER_VALUE, true);

    assertThat(coins.balance(user)).isZero();
    Map<String, Object> r = reversal(ret);
    assertThat(((Number) r.get("earn_due")).intValue()).isEqualTo(40);
    assertThat(((Number) r.get("earn_taken")).intValue()).isEqualTo(5);

    // the user earns again, then the event is redelivered: nothing may change (C4)
    ledger(user, 50, "ADJUST", "topup");
    int before = coins.balance(user);
    coins.onReturnRefunded(order, user, ret, ORDER_VALUE, ORDER_VALUE, true);
    assertThat(coins.balance(user)).isEqualTo(before);
    assertThat(rows(user, "return-%-" + ret)).isEqualTo(1);
    assertThat(reversalRows(order)).isEqualTo(1);
  }

  @Test
  @DisplayName("a clamp to 0 still records the reversal row (no ledger row possible)")
  void fullyClampedStillRecorded() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    UUID ret = UUID.randomUUID();
    ledger(user, 20, "EARN_ORDER", order.toString());
    ledger(user, -20, "REDEEM_ORDER", UUID.randomUUID().toString()); // balance 0

    coins.onReturnRefunded(order, user, ret, ORDER_VALUE, ORDER_VALUE, true);

    assertThat(rows(user, "return-%-" + ret)).isZero();
    assertThat(((Number) reversal(ret).get("earn_taken")).intValue()).isZero();
    assertThat(((Number) reversal(ret).get("earn_due")).intValue()).isEqualTo(20);
  }

  @Test
  @DisplayName("an order with no redeemed or earned coins writes no zero rows")
  void noZeroRows() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    UUID ret = UUID.randomUUID();

    coins.onReturnRefunded(order, user, ret, THIRD, ORDER_VALUE, false);

    assertThat(rows(user, "return-%")).isZero();
    assertThat(reversalRows(order)).isEqualTo(1);
  }

  @Test
  @DisplayName("two partial returns and a completing one total exactly N and E")
  void threeReturnsExact() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    seed(user, order, 10, 10, 100);
    List<UUID> rets = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    coins.onReturnRefunded(order, user, rets.get(0), THIRD, ORDER_VALUE, false);
    coins.onReturnRefunded(order, user, rets.get(1), THIRD, ORDER_VALUE, false);
    coins.onReturnRefunded(order, user, rets.get(2), THIRD, ORDER_VALUE, true);

    assertThat(sum(user, "return-redeem-")).isEqualTo(10);
    assertThat(sum(user, "return-earn-")).isEqualTo(-10);
    assertThat(((Number) reversal(rets.get(2)).get("earn_due")).intValue()).isEqualTo(4);
  }

  @Test
  @DisplayName("a single full return gives back all N and reverses all E")
  void fullReturn() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    seed(user, order, 17, 23, 100);

    coins.onReturnRefunded(order, user, UUID.randomUUID(), ORDER_VALUE, ORDER_VALUE, true);

    assertThat(sum(user, "return-redeem-")).isEqualTo(17);
    assertThat(sum(user, "return-earn-")).isEqualTo(-23);
  }

  @Test
  @DisplayName(
      "a completing return processed before an earlier return still totals exactly N and E")
  void completingFirstStaysCapped() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    seed(user, order, 10, 10, 100);

    coins.onReturnRefunded(order, user, UUID.randomUUID(), THIRD, ORDER_VALUE, true);
    coins.onReturnRefunded(order, user, UUID.randomUUID(), THIRD, ORDER_VALUE, false);
    coins.onReturnRefunded(order, user, UUID.randomUUID(), THIRD, ORDER_VALUE, false);

    assertThat(sum(user, "return-redeem-")).isEqualTo(10);
    assertThat(sum(user, "return-earn-")).isEqualTo(-10);
  }

  @Test
  @DisplayName("legacy redeem-/earn- rows count as prior so nothing is given or taken twice")
  void legacyRowsCountAsPrior() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    seed(user, order, 10, 10, 100);
    ledger(user, 10, "REVERSE_ORDER", "redeem-" + order);
    ledger(user, -10, "REVERSE_ORDER", "earn-" + order);

    coins.onReturnRefunded(order, user, UUID.randomUUID(), ORDER_VALUE, ORDER_VALUE, true);

    assertThat(sum(user, "return-redeem-")).isZero();
    assertThat(sum(user, "return-earn-")).isZero();
  }

  @Test
  @DisplayName("the redeemed give-back is applied before the earn clamp")
  void giveBackBeforeClamp() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    seed(user, order, 10, 10, 0); // balance 0

    coins.onReturnRefunded(order, user, UUID.randomUUID(), ORDER_VALUE, ORDER_VALUE, true);

    assertThat(sum(user, "return-redeem-")).isEqualTo(10);
    assertThat(sum(user, "return-earn-")).isEqualTo(-10); // clamp saw the +10 first
    assertThat(coins.balance(user)).isZero();
  }

  @Test
  @DisplayName("a late redelivered return event through the real listener is applied once")
  void lateReplayThroughListener() {
    UUID user = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    UUID ret = UUID.randomUUID();
    seed(user, order, 10, 10, 100);
    String old = java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS).toString();
    OrderEvent e =
        new OrderEvent(
            "e1",
            "RETURN_REFUNDED",
            order.toString(),
            user.toString(),
            null,
            "REFUNDED",
            ORDER_VALUE,
            "INR",
            old,
            ret.toString(),
            ORDER_VALUE,
            ORDER_VALUE);

    listener.onOrderEvent(e);
    listener.onOrderEvent(e);

    assertThat(sum(user, "return-redeem-")).isEqualTo(10);
    assertThat(sum(user, "return-earn-")).isEqualTo(-10);
    assertThat(reversalRows(order)).isEqualTo(1);
  }
}
