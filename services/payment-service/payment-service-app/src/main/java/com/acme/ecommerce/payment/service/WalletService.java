package com.acme.ecommerce.payment.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.client.NotificationFeignClient;
import com.acme.ecommerce.payment.client.NotificationFeignClient.Inbox;
import com.acme.ecommerce.payment.repository.WalletRepository;
import com.acme.ecommerce.payment.repository.WalletRepository.Txn;
import com.acme.ecommerce.payment.repository.WalletRepository.Wallet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Store wallet: one currency per wallet, every movement is an idempotent ledger row. */
@Service
public class WalletService {

  private static final Set<String> CREDIT_REASONS =
      Set.of("REFUND", "GIFT_CARD", "REFERRAL", "ADJUSTMENT", "CASHBACK");

  /** Balance snapshot; currency is null when the user has no wallet yet. */
  public record Balance(BigDecimal balance, String currency) {}

  public record WalletView(BigDecimal balance, String currency, List<Txn> transactions) {}

  private static final Logger log = LoggerFactory.getLogger(WalletService.class);

  private final WalletRepository wallets;
  private final TransactionTemplate tx;
  private final NotificationFeignClient notifications;

  public WalletService(
      WalletRepository wallets, TransactionTemplate tx, NotificationFeignClient notifications) {
    this.wallets = wallets;
    this.tx = tx;
    this.notifications = notifications;
  }

  public Balance balance(UUID userId) {
    return wallets
        .find(userId)
        .map(w -> new Balance(w.balance(), w.currency()))
        .orElse(new Balance(BigDecimal.ZERO.setScale(2), null));
  }

  public WalletView view(UUID userId) {
    Balance b = balance(userId);
    return new WalletView(
        b.balance(), b.currency() == null ? "INR" : b.currency(), wallets.recent(userId, 50));
  }

  /** Idempotent by key (a replay returns the current balance without a second credit). */
  public Balance credit(
      UUID userId,
      BigDecimal rawAmount,
      String rawCurrency,
      String reason,
      String referenceId,
      String key) {
    String why = reason == null ? "ADJUSTMENT" : reason.toUpperCase(Locale.ROOT);
    if (!CREDIT_REASONS.contains(why)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Unknown wallet credit reason");
    }
    BigDecimal amount = positive(rawAmount);
    String currency = rawCurrency.toUpperCase(Locale.ROOT);
    AtomicBoolean fresh = new AtomicBoolean(false);
    Balance result =
        tx.execute(
        status -> {
          wallets.ensure(userId, currency);
          Wallet w = wallets.lock(userId).orElseThrow();
          if (wallets.txnExists(key)) {
            return new Balance(w.balance(), w.currency());
          }
          String walletCurrency = w.currency();
          if (!walletCurrency.equals(currency)) {
            if (w.balance().signum() != 0) {
              throw new CodedException(
                  ErrorCode.CURRENCY_MISMATCH,
                  "Wallet is held in " + walletCurrency + ", cannot credit " + currency);
            }
            walletCurrency = currency;
          }
          BigDecimal after = w.balance().add(amount);
          wallets.update(userId, after, walletCurrency);
          wallets.insertTxn(userId, "CREDIT", amount, walletCurrency, why, referenceId, key, after);
          fresh.set(true);
          return new Balance(after, walletCurrency);
        });
    if (fresh.get()) {
      notifyCredit(userId, amount, result.currency(), why);
    }
    return result;
  }

  /** Inbox entry for a new wallet credit; best effort, never fails the credit. */
  private void notifyCredit(UUID userId, BigDecimal amount, String currency, String reason) {
    String what =
        switch (reason) {
          case "GIFT_CARD" -> "Gift card redeemed";
          case "REFUND" -> "Refund added to your wallet";
          case "REFERRAL" -> "Referral reward added to your wallet";
          default -> "Wallet credited";
        };
    try {
      notifications.inbox(
          new Inbox(
              userId.toString(),
              "ACCOUNT",
              what,
              amount.toPlainString() + " " + currency + " is now available in your wallet.",
              "/account/wallet"));
    } catch (RuntimeException e) {
      log.warn("Could not post wallet inbox entry for {}: {}", userId, e.toString());
    }
  }

  /** Idempotent by key; 409 INSUFFICIENT_BALANCE when funds (or the currency) do not fit. */
  public Balance debit(
      UUID userId, BigDecimal rawAmount, String rawCurrency, UUID orderId, String key) {
    BigDecimal amount = positive(rawAmount);
    String currency = rawCurrency.toUpperCase(Locale.ROOT);
    return tx.execute(
        status -> {
          Wallet w = wallets.lock(userId).orElse(null);
          if (w == null) {
            throw insufficient();
          }
          if (wallets.txnExists(key)) {
            return new Balance(w.balance(), w.currency());
          }
          if (!w.currency().equals(currency) || w.balance().compareTo(amount) < 0) {
            throw insufficient();
          }
          BigDecimal after = w.balance().subtract(amount);
          wallets.update(userId, after, w.currency());
          wallets.insertTxn(
              userId, "DEBIT", amount, w.currency(), "ORDER", String.valueOf(orderId), key, after);
          return new Balance(after, w.currency());
        });
  }

  private static CodedException insufficient() {
    return new CodedException(ErrorCode.INSUFFICIENT_BALANCE, "Insufficient wallet balance");
  }

  private static BigDecimal positive(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Amount must be positive");
    }
    return amount.setScale(2, RoundingMode.HALF_UP);
  }
}
