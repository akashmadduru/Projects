package com.acme.ecommerce.order.service;

import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Rule-based fraud score at placement (Phase 10). Score &gt;= {@value #BLOCK_COD} blocks cash on
 * delivery; &gt;= {@value #HOLD} places the order with {@code riskHold} until an admin releases it.
 * Weights are tuned so no single rule (nor two of the cheap ones) blocks a normal order.
 */
@Service
public class RiskService {

  public static final int BLOCK_COD = 70;
  public static final int HOLD = 90;

  private static final int W_VELOCITY = 40;
  private static final int W_AVG = 35;
  private static final int W_NEW_ACCOUNT = 25;
  private static final int W_COD_REFUSALS = 35;

  public record Assessment(int score, List<String> reasons) {
    public boolean blocksCod() {
      return score >= BLOCK_COD;
    }

    public boolean hold() {
      return score >= HOLD;
    }
  }

  private final OrderRepository orders;

  /** Orders already placed in the last 10 minutes at which the velocity rule fires (prod: 3). */
  private final long velocityThreshold;

  /** Prior non-cancelled orders needed before the average-total rule applies (prod: 3). */
  private final long avgMinOrders;

  /** Multiple of the account's average total that trips the average-total rule (prod: 3). */
  private final BigDecimal avgMultiplier;

  public RiskService(
      OrderRepository orders,
      @Value("${order.risk.velocity-threshold:3}") long velocityThreshold,
      @Value("${order.risk.avg-min-orders:3}") long avgMinOrders,
      @Value("${order.risk.avg-multiplier:3}") BigDecimal avgMultiplier) {
    this.orders = orders;
    this.velocityThreshold = velocityThreshold;
    this.avgMinOrders = avgMinOrders;
    this.avgMultiplier = avgMultiplier;
  }

  public Assessment assess(UUID userId, BigDecimal total, String currency) {
    int score = 0;
    List<String> reasons = new ArrayList<>();
    Instant now = Instant.now();

    long recent = orders.countByUserIdAndPlacedAtAfter(userId, now.minus(Duration.ofMinutes(10)));
    if (recent >= velocityThreshold) {
      score += W_VELOCITY;
      reasons.add("More than " + velocityThreshold + " orders in 10 minutes");
    }

    List<Object[]> agg = orders.orderStats(userId, currency, OrderStatus.CANCELLED);
    if (!agg.isEmpty() && agg.get(0)[0] != null) {
      long count = ((Number) agg.get(0)[0]).longValue();
      BigDecimal avg = new BigDecimal(agg.get(0)[1].toString());
      if (count >= avgMinOrders
          && avg.signum() > 0
          && total.compareTo(avg.multiply(avgMultiplier)) > 0) {
        score += W_AVG;
        reasons.add(
            "Order total is more than "
                + avgMultiplier.stripTrailingZeros().toPlainString()
                + "x the account average");
      }
    }

    Instant first = orders.firstOrderAt(userId);
    boolean newAccount = first == null || first.isAfter(now.minus(Duration.ofHours(24)));
    BigDecimal large = "INR".equalsIgnoreCase(currency) ? BigDecimal.valueOf(5000) : BigDecimal.valueOf(100);
    if (newAccount && total.compareTo(large) > 0) {
      score += W_NEW_ACCOUNT;
      reasons.add("New account with a large order");
    }

    long refused = orders.codRefusals(userId, now.minus(Duration.ofDays(30)));
    if (refused > 2) {
      score += W_COD_REFUSALS;
      reasons.add("Several cash-on-delivery orders refused or cancelled after shipping");
    }
    return new Assessment(score, reasons);
  }
}
