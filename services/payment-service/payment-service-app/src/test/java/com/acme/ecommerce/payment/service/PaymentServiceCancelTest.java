package com.acme.ecommerce.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.config.PaymentProperties;
import com.acme.ecommerce.payment.repository.PaymentRepository;
import com.acme.ecommerce.payment.repository.PaymentRepository.Payment;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

/** TD-P6-03: an order cancelled before payment voids its payment. */
class PaymentServiceCancelTest {

  private final UUID order = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();

  private PaymentRepository repo;
  private StripeGateway stripe;
  private RazorpayGateway razorpay;
  private PaymentService service;

  @BeforeEach
  void setUp() {
    repo = mock(PaymentRepository.class);
    stripe = mock(StripeGateway.class);
    razorpay = mock(RazorpayGateway.class);
    PaymentProperties props = new PaymentProperties();
    props.getStripe().setSecretKey("sk_test_x");
    when(stripe.enabled()).thenReturn(true);
    service =
        new PaymentService(
            repo,
            stripe,
            razorpay,
            new PaymentProviders(props, new MockEnvironment()),
            mock(OrderReporter.class),
            mock(WalletService.class),
            props,
            mock(TransactionTemplate.class));
  }

  private Payment payment(String method, String mode, String status, String intentId) {
    Payment p =
        new Payment(
            paymentId,
            order,
            UUID.randomUUID(),
            method,
            mode,
            new BigDecimal("499.00"),
            "INR",
            status,
            intentId,
            BigDecimal.ZERO,
            false,
            "ORDER",
            order,
            "RAZORPAY".equals(mode) ? "order_9" : null,
            null);
    when(repo.findByOrderId(order)).thenReturn(Optional.of(p));
    when(repo.findById(paymentId)).thenReturn(Optional.of(p));
    return p;
  }

  @Test
  @DisplayName("an unpaid Stripe payment has its intent cancelled and is voided")
  void stripeIntentCancelledAndVoided() {
    payment("CARD", "STRIPE", "REQUIRES_PAYMENT", "pi_1");

    service.cancelUnpaid(order);

    verify(stripe).cancel("pi_1");
    verify(repo).voidUnpaid(paymentId);
  }

  @Test
  @DisplayName(
      "if Stripe refuses the cancel (paid a moment ago) the payment is left for the"
          + " late-payment refund")
  void stripeCancelFailureLeavesPayment() {
    payment("CARD", "STRIPE", "REQUIRES_PAYMENT", "pi_1");
    doThrow(new CodedException(ErrorCode.UPSTREAM_UNAVAILABLE, "already succeeded"))
        .when(stripe)
        .cancel("pi_1");

    service.cancelUnpaid(order);

    verify(repo, never()).voidUnpaid(any());
  }

  @Test
  @DisplayName("a Razorpay order cannot be cancelled, so it is left open")
  void razorpayLeftOpen() {
    payment("CARD", "RAZORPAY", "REQUIRES_PAYMENT", null);

    service.cancelUnpaid(order);

    verify(repo, never()).voidUnpaid(any());
  }

  @Test
  @DisplayName("a paid or cash-on-delivery payment is never touched")
  void paidAndCodUntouched() {
    payment("CARD", "STRIPE", "SUCCEEDED", "pi_1");
    service.cancelUnpaid(order);
    payment("COD", "STRIPE", "COD_PENDING", null);
    service.cancelUnpaid(order);

    verify(stripe, never()).cancel(any());
    verify(repo, never()).voidUnpaid(any());
  }

  @Test
  @DisplayName("SIMULATED payments are voided without any provider call")
  void simulatedVoided() {
    payment("CARD", "SIMULATED", "REQUIRES_PAYMENT", null);

    service.cancelUnpaid(order);

    verify(stripe, never()).cancel(any());
    verify(repo).voidUnpaid(paymentId);
  }
}
