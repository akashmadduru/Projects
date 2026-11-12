package com.acme.ecommerce.payment.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Wallet + ledger persistence on plain SQL. */
@Repository
public class WalletRepository {

  public record Wallet(UUID userId, BigDecimal balance, String currency) {}

  public record Txn(
      UUID id,
      String type,
      BigDecimal amount,
      String currency,
      String reason,
      String referenceId,
      BigDecimal balanceAfter,
      Instant createdAt) {}

  private final JdbcClient jdbc;

  public WalletRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Wallet> find(UUID userId) {
    return jdbc.sql("SELECT user_id, balance, currency FROM payment.wallets WHERE user_id = :u")
        .param("u", userId)
        .query(
            (rs, n) -> new Wallet(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3)))
        .optional();
  }

  public Optional<Wallet> lock(UUID userId) {
    return jdbc.sql(
            "SELECT user_id, balance, currency FROM payment.wallets WHERE user_id = :u FOR UPDATE")
        .param("u", userId)
        .query(
            (rs, n) -> new Wallet(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3)))
        .optional();
  }

  public void ensure(UUID userId, String currency) {
    jdbc.sql(
            "INSERT INTO payment.wallets (user_id, balance, currency) VALUES (:u, 0, :c) "
                + "ON CONFLICT DO NOTHING")
        .param("u", userId)
        .param("c", currency)
        .update();
  }

  public void update(UUID userId, BigDecimal balance, String currency) {
    jdbc.sql(
            "UPDATE payment.wallets SET balance = :b, currency = :c, updated_at = now() "
                + "WHERE user_id = :u")
        .param("b", balance)
        .param("c", currency)
        .param("u", userId)
        .update();
  }

  public boolean txnExists(String key) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM payment.wallet_txns WHERE idempotency_key = :k)")
        .param("k", key)
        .query(Boolean.class)
        .single();
  }

  public void insertTxn(
      UUID userId,
      String type,
      BigDecimal amount,
      String currency,
      String reason,
      String referenceId,
      String key,
      BigDecimal balanceAfter) {
    jdbc.sql(
            "INSERT INTO payment.wallet_txns (user_id, type, amount, currency, reason, "
                + "reference_id, idempotency_key, balance_after) "
                + "VALUES (:u, :t, :a, :c, :r, :ref, :k, :b)")
        .param("u", userId)
        .param("t", type)
        .param("a", amount)
        .param("c", currency)
        .param("r", reason)
        .param("ref", referenceId)
        .param("k", key)
        .param("b", balanceAfter)
        .update();
  }

  public List<Txn> recent(UUID userId, int limit) {
    return jdbc.sql(
            "SELECT id, type, amount, currency, reason, reference_id, balance_after, created_at "
                + "FROM payment.wallet_txns WHERE user_id = :u ORDER BY created_at DESC, id LIMIT :l")
        .param("u", userId)
        .param("l", limit)
        .query(
            (rs, n) ->
                new Txn(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getBigDecimal(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getBigDecimal(7),
                    rs.getTimestamp(8).toInstant()))
        .list();
  }
}
