package com.acme.ecommerce.payment.web;

import com.acme.ecommerce.common.core.net.ClientAddress;
import com.acme.ecommerce.common.web.net.ClientIp;
import com.acme.ecommerce.payment.api.dto.CreatePaymentRequest;
import com.acme.ecommerce.payment.api.dto.PaymentResponse;
import com.acme.ecommerce.payment.service.GiftCardService;
import com.acme.ecommerce.payment.service.GiftCardService.GiftCardView;
import com.acme.ecommerce.payment.service.GiftCardService.Pending;
import com.acme.ecommerce.payment.service.PaymentService;
import com.acme.ecommerce.payment.service.WalletService;
import com.acme.ecommerce.payment.service.WalletService.WalletView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer wallet and gift cards (JWT). */
@RestController
public class WalletController {

  public record BuyGiftCardRequest(
      @NotNull BigDecimal amount,
      @NotNull @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
      @NotBlank @Email @Size(max = 320) String recipientEmail,
      @Size(max = 500) String message) {}

  public record GiftCardRef(UUID id, String status) {}

  public record BuyGiftCardResponse(GiftCardRef giftCard, PaymentBlock payment) {}

  public record PaymentBlock(
      UUID paymentId, String status, String mode, String clientSecret, String publishableKey) {}

  public record RedeemRequest(@NotBlank @Size(max = 40) String code) {}

  private final WalletService wallet;
  private final GiftCardService giftCards;
  private final PaymentService payments;

  public WalletController(
      WalletService wallet, GiftCardService giftCards, PaymentService payments) {
    this.wallet = wallet;
    this.giftCards = giftCards;
    this.payments = payments;
  }

  @GetMapping("/api/wallet")
  public WalletView wallet(@AuthenticationPrincipal Jwt jwt) {
    return wallet.view(UUID.fromString(jwt.getSubject()));
  }

  @PostMapping("/api/gift-cards")
  @ResponseStatus(HttpStatus.CREATED)
  public BuyGiftCardResponse buy(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BuyGiftCardRequest req) {
    UUID userId = UUID.fromString(jwt.getSubject());
    Pending card =
        giftCards.createPending(
            userId,
            jwt.getClaimAsString("email"),
            req.amount(),
            req.currency(),
            req.recipientEmail(),
            req.message());
    PaymentResponse p =
        payments.create(
            new CreatePaymentRequest(
                null,
                userId,
                card.amount(),
                card.currency(),
                "CARD",
                "GIFT_CARD",
                card.id(),
                null));
    return new BuyGiftCardResponse(
        new GiftCardRef(card.id(), giftCards.statusOf(card.id())),
        new PaymentBlock(
            p.paymentId(), p.status(), p.mode(), p.clientSecret(), p.publishableKey()));
  }

  @PostMapping("/api/gift-cards/redeem")
  public WalletView redeem(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RedeemRequest req,
      jakarta.servlet.http.HttpServletRequest http) {
    UUID userId = UUID.fromString(jwt.getSubject());
    // The gateway sets X-Internal-Client-Ip (TD-P8-21 per-IP limit); null = unknown, so unknown
    // callers are not lumped into one shared bucket.
    String ip = ClientIp.resolve(http).map(ClientAddress::format).orElse(null);
    giftCards.redeem(userId, req.code(), ip);
    return wallet.view(userId);
  }

  /** Emails the recipient a new code (the old one stops working). Purchaser only, rate limited. */
  @PostMapping("/api/gift-cards/{id}/resend")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    giftCards.resend(UUID.fromString(jwt.getSubject()), id);
  }

  @GetMapping("/api/gift-cards")
  public List<GiftCardView> mine(@AuthenticationPrincipal Jwt jwt) {
    return giftCards.listPurchased(UUID.fromString(jwt.getSubject()));
  }
}
