package com.acme.ecommerce.promotion.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.promotion.client.UserFeignClient;
import com.acme.ecommerce.promotion.config.PromotionProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loyalty coins ledger. Every row is idempotent by (reason, reference, user). 1 coin = 1 INR or
 * 0.01 of any other currency.
 */
@Service
public class CoinService {

  private static final Logger log = LoggerFactory.getLogger(CoinService.class);

  public record Entry(UUID id, int delta, String reason, String referenceId, Instant createdAt, Instant expiresAt) {}

  public record CoinsView(
      int balance, BigDecimal value, String currency, List<Entry> history, int expiringSoon) {}

  private final JdbcClient jdbc;
  private final PromotionProperties props;
  private final MembershipLookup members;
  private final UserFeignClient users;

  public CoinService(
      JdbcClient jdbc, PromotionProperties props, MembershipLookup members, UserFeignClient users) {
    this.jdbc = jdbc;
    this.props = props;
    this.members = members;
    this.users = users;
  }

  // ---- reads ---------------------------------------------------------------------------------

  public int balance(UUID userId) {
    return jdbc.sql("SELECT COALESCE(SUM(delta), 0) FROM promotion.coin_ledger WHERE user_id = :u")
        .param("u", userId)
        .query(Integer.class)
        .single();
  }

  public static BigDecimal valueOf(int coins, String currency) {
    BigDecimal per = "INR".equalsIgnoreCase(currency) ? BigDecimal.ONE : new BigDecimal("0.01");
    return per.multiply(BigDecimal.valueOf(coins)).setScale(2, RoundingMode.HALF_UP);
  }

  public CoinsView view(UUID userId, String currency) {
    String cur = currency == null || currency.isBlank() ? "INR" : currency.toUpperCase(Locale.ROOT);
    int balance = balance(userId);
    List<Entry> history =
        jdbc.sql(
                "SELECT id, delta, reason, reference_id, created_at, expires_at "
                    + "FROM promotion.coin_ledger WHERE user_id = :u "
                    + "ORDER BY created_at DESC, id LIMIT 50")
            .param("u", userId)
            .query(
                (rs, n) ->
                    new Entry(
                        rs.getObject("id", UUID.class),
                        rs.getInt("delta"),
                        rs.getString("reason"),
                        rs.getString("reference_id"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class) == null
                            ? null
                            : rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
            .list();
    int soon =
        jdbc.sql(
                "SELECT COALESCE(SUM(delta), 0) FROM promotion.coin_ledger WHERE user_id = :u "
                    + "AND delta > 0 AND expires_at IS NOT NULL AND expires_at > now() "
                    + "AND expires_at < now() + interval '30 days'")
            .param("u", userId)
            .query(Integer.class)
            .single();
    return new CoinsView(balance, valueOf(balance, cur), cur, history, Math.min(soon, balance));
  }

  // ---- checkout redemption -------------------------------------------------------------------

  /** Idempotent by orderId; 409 INSUFFICIENT_BALANCE when the user has fewer coins. */
  @Transactional
  public void redeem(UUID userId, int coins, UUID orderId) {
    if (coins <= 0) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "coins must be positive");
    }
    lock(userId);
    if (exists("REDEEM_ORDER", orderId.toString(), userId)) {
      return;
    }
    if (balance(userId) < coins) {
      throw new CodedException(ErrorCode.INSUFFICIENT_BALANCE, "Not enough coins");
    }
    insert(userId, -coins, "REDEEM_ORDER", orderId.toString(), null);
  }

  /** Gives redeemed coins back (cancel / payment failure / expiry). Idempotent, no-op if none. */
  @Transactional
  public void releaseRedeem(UUID orderId) {
    var row =
        jdbc.sql(
                "SELECT user_id, delta FROM promotion.coin_ledger "
                    + "WHERE reason = 'REDEEM_ORDER' AND reference_id = :r")
            .param("r", orderId.toString())
            .query((rs, n) -> new Object[] {rs.getObject("user_id", UUID.class), rs.getInt("delta")})
            .optional()
            .orElse(null);
    if (row == null) {
      return;
    }
    UUID userId = (UUID) row[0];
    lock(userId);
    insert(userId, -((Integer) row[1]), "REVERSE_ORDER", "redeem-" + orderId, null);
  }

  // ---- order events --------------------------------------------------------------------------

  /** Credits coins for a delivered order (members earn 2x) and the referral reward if due. */
  @Transactional
  public void onDelivered(UUID orderId, UUID userId, BigDecimal total, String currency) {
    int unit =
        "INR".equalsIgnoreCase(currency)
            ? props.getCoins().getEarnUnitInr()
            : props.getCoins().getEarnUnitOther();
    int base = total == null ? 0 : total.divideToIntegralValue(BigDecimal.valueOf(unit)).intValue();
    if (base > 0) {
      int coins = members.isMember(userId) ? base * 2 : base;
      lock(userId);
      if (insert(userId, coins, "EARN_ORDER", orderId.toString(), expiry())) {
        log.info("Credited {} coins to {} for order {}", coins, userId, orderId);
      }
    }
    referralReward(userId);
  }

  /** Full refund: take back the coins this order earned (clamped to the current balance). */
  @Transactional
  public void onFullyRefunded(UUID orderId, UUID userId) {
    Integer earned =
        jdbc.sql(
                "SELECT delta FROM promotion.coin_ledger WHERE reason = 'EARN_ORDER' "
                    + "AND reference_id = :r AND user_id = :u")
            .param("r", orderId.toString())
            .param("u", userId)
            .query(Integer.class)
            .optional()
            .orElse(null);
    if (earned == null) {
      return;
    }
    lock(userId);
    int take = Math.min(earned, Math.max(0, balance(userId)));
    if (take > 0) {
      insert(userId, -take, "REVERSE_ORDER", "earn-" + orderId, null);
    }
  }

  private void referralReward(UUID refereeId) {
    if (exists("REFERRAL", "referee-" + refereeId, refereeId)) {
      return;
    }
    UUID referrer = null;
    try {
      UserFeignClient.Referral n = users.referralByReferee(refereeId);
      if (n != null && !n.rewarded()) {
        referrer = n.referrerUserId();
      }
    } catch (RuntimeException e) {
      log.debug("No referral for {}: {}", refereeId, e.toString());
      return;
    }
    if (referrer == null || referrer.equals(refereeId)) {
      return;
    }
    int reward = props.getCoins().getReferralReward();
    lock(refereeId);
    insert(refereeId, reward, "REFERRAL", "referee-" + refereeId, expiry());
    lock(referrer);
    insert(referrer, reward, "REFERRAL", "referrer-" + refereeId, expiry());
    try {
      users.referralRewarded(refereeId);
    } catch (RuntimeException e) {
      log.warn("Could not mark referral rewarded for {}: {}", refereeId, e.toString());
    }
    log.info("Referral reward of {} coins credited to {} and {}", reward, referrer, refereeId);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private Instant expiry() {
    return Instant.now().plus(props.getCoins().getExpiryDays(), ChronoUnit.DAYS);
  }

  private void lock(UUID userId) {
    jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:u, 0))) s")
        .param("u", userId.toString())
        .query(Integer.class)
        .single();
  }

  private boolean exists(String reason, String ref, UUID userId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM promotion.coin_ledger "
                + "WHERE reason = :r AND reference_id = :ref AND user_id = :u)")
        .param("r", reason)
        .param("ref", ref)
        .param("u", userId)
        .query(Boolean.class)
        .single();
  }

  private boolean insert(UUID userId, int delta, String reason, String ref, Instant expiresAt) {
    return jdbc.sql(
                "INSERT INTO promotion.coin_ledger (user_id, delta, reason, reference_id, expires_at) "
                    + "VALUES (:u, :d, :r, :ref, :e) ON CONFLICT DO NOTHING")
            .param("u", userId)
            .param("d", delta)
            .param("r", reason)
            .param("ref", ref)
            .param("e", expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
            .update()
        > 0;
  }
}
