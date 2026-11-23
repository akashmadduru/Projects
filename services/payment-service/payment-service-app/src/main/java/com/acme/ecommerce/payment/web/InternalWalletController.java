package com.acme.ecommerce.payment.web;

import com.acme.ecommerce.payment.service.WalletService;
import com.acme.ecommerce.payment.service.WalletService.Balance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service wallet endpoints (X-Internal-Token); never routed by the gateway. */
@RestController
@RequestMapping("/internal/wallet")
public class InternalWalletController {

  public record DebitRequest(
      @NotNull UUID userId,
      @NotNull BigDecimal amount,
      @NotBlank String currency,
      @NotNull UUID orderId,
      @NotBlank @Size(max = 150) String idempotencyKey) {}

  public record CreditRequest(
      @NotNull UUID userId,
      @NotNull BigDecimal amount,
      @NotBlank String currency,
      @NotBlank String reason,
      @Size(max = 100) String referenceId,
      @NotBlank @Size(max = 150) String idempotencyKey) {}

  public record ReverseRequest(
      @NotNull UUID userId,
      @NotBlank @Size(max = 150) String debitKey,
      @NotBlank @Size(max = 150) String creditKey) {}

  public record ReverseResult(boolean reversed) {}

  /** Always 200; only these fields, never a balance or a transaction id. */
  public record CreditLookup(boolean found, UUID userId, BigDecimal amount, String currency) {}

  private final WalletService wallet;

  public InternalWalletController(WalletService wallet) {
    this.wallet = wallet;
  }

  @GetMapping("/{userId}")
  public Balance balance(@PathVariable UUID userId) {
    return wallet.balance(userId);
  }

  /** Was a credit made under this key? Internal only; the answer names no balance or txn id. */
  @GetMapping("/credits/{key}")
  public CreditLookup credit(@PathVariable String key) {
    return wallet
        .findCredit(key)
        .map(c -> new CreditLookup(true, c.userId(), c.amount(), c.currency()))
        .orElse(new CreditLookup(false, null, null, null));
  }

  @PostMapping("/debit")
  public Balance debit(@Valid @RequestBody DebitRequest r) {
    return wallet.debit(r.userId(), r.amount(), r.currency(), r.orderId(), r.idempotencyKey());
  }

  /** Returns a debit only if it happened (ambiguous failures, TD-P8-23). Idempotent. */
  @PostMapping("/reverse")
  public ReverseResult reverse(@Valid @RequestBody ReverseRequest r) {
    return new ReverseResult(wallet.reverseDebit(r.userId(), r.debitKey(), r.creditKey()));
  }

  @PostMapping("/credit")
  public Balance credit(@Valid @RequestBody CreditRequest r) {
    return wallet.credit(
        r.userId(), r.amount(), r.currency(), r.reason(), r.referenceId(), r.idempotencyKey());
  }
}
