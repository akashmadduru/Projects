package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.payment.api.dto.CreatePaymentRequest;
import com.acme.ecommerce.payment.api.dto.RefundRequest;
import com.acme.ecommerce.payment.config.PaymentProperties;
import com.acme.ecommerce.payment.repository.PaymentRepository;
import com.acme.ecommerce.payment.repository.PaymentRepository.Payment;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Razorpay and multi-provider behaviour of PaymentService, with the providers mocked. */
class PaymentServiceRazorpayTest {

  private static final String WEBHOOK_SECRET = "whsec";

  private final UUID subject = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();

  private PaymentRepository repo;
  private StripeGateway stripe;
  private RazorpayGateway razorpay;
  private OrderReporter reporter;
  private TransactionTemplate tx;
  private PaymentProperties props;
  private PaymentService service;

  @BeforeEach
  void setUp() {
    repo = mock(PaymentRepository.class);
    stripe = mock(StripeGateway.class);
    reporter = mock(OrderReporter.class);
    tx = mock(TransactionTemplate.class);
    props = new PaymentProperties();
    props.getRazorpay().setKeyId("rzp_test_id");
    props.getRazorpay().setKeySecret("secret");
    props.getRazorpay().setWebhookSecret(WEBHOOK_SECRET);
    razorpay = new RazorpayGateway(props);
    razorpay = org.mockito.Mockito.spy(razorpay);
    when(stripe.enabled()).thenReturn(false);
    PaymentProviders providers = new PaymentProviders(props, new MockEnvironment());
    service =
        new PaymentService(
            repo, stripe, razorpay, providers, reporter, mock(WalletService.class), props, tx);
  }

  private Payment payment(String mode, String status, String currency) {
    return new Payment(
        paymentId,
        subject,
        user,
        "CARD",
        mode,
        new BigDecimal("499.00"),
        currency,
        status,
        null,
        BigDecimal.ZERO,
        false,
        "ORDER",
        subject,
        "order_9",
        null);
  }

  private void stubFind(Payment p) {
    when(repo.findById(paymentId)).thenReturn(Optional.of(p));
    when(repo.findByOrderId(subject)).thenReturn(Optional.of(p));
    when(repo.findByProviderOrderId("order_9")).thenReturn(Optional.of(p));
  }

  private static String captured(String orderId, long amount, String currency) {
    return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
        + "\"id\":\"pay_1\",\"order_id\":\""
        + orderId
        + "\",\"amount\":"
        + amount
        + ",\"currency\":\""
        + currency
        + "\"}}}}";
  }

  private static String sign(String body) {
    return RazorpayGateway.hmacHex(WEBHOOK_SECRET, body);
  }

  @Test
  @DisplayName("create with Razorpay as the only provider makes a Razorpay order")
  void createUsesRazorpay() {
    org.mockito.Mockito.doReturn(new RazorpayGateway.Order("order_9", "created", 49900, "INR"))
        .when(razorpay)
        .createOrder(anyString(), eq(49900L), eq("INR"));
    when(repo.findByOrderId(subject))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(payment("RAZORPAY", "REQUIRES_PAYMENT", "INR")));

    var res =
        service.create(
            new CreatePaymentRequest(
                subject, user, new BigDecimal("499"), "inr", "CARD", null, null, null));

    verify(repo)
        .insert(
            eq(subject),
            eq(user),
            eq("CARD"),
            eq("RAZORPAY"),
            any(),
            eq("INR"),
            eq("REQUIRES_PAYMENT"),
            eq(null),
            eq("order_9"),
            eq("ORDER"));
    assertThat(res.mode()).isEqualTo("RAZORPAY");
    assertThat(res.providerOrderId()).isEqualTo("order_9");
    assertThat(res.publishableKey()).isEqualTo("rzp_test_id");
    assertThat(res.clientSecret()).isNull();
  }

  @Test
  @DisplayName("Razorpay refuses non-INR payments")
  void razorpayIsInrOnly() {
    when(repo.findByOrderId(subject)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    new CreatePaymentRequest(
                        subject, user, new BigDecimal("10"), "USD", "CARD", null, null, null)))
        .isInstanceOf(CodedException.class)
        .hasMessageContaining("INR");
    verify(repo, never())
        .insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("verify with a valid signature settles the payment")
  void verifyAccepts() {
    stubFind(payment("RAZORPAY", "REQUIRES_PAYMENT", "INR"));
    when(repo.applyOutcome(paymentId, "SUCCEEDED")).thenReturn(1);
    String sig = RazorpayGateway.hmacHex("secret", "order_9|pay_1");

    service.verifyRazorpay(paymentId, user, "order_9", "pay_1", sig);

    verify(repo).setProviderPaymentId(paymentId, "pay_1");
    verify(repo).applyOutcome(paymentId, "SUCCEEDED");
  }

  @Test
  @DisplayName("verify rejects a forged signature, another user's payment and another order")
  void verifyRejects() {
    stubFind(payment("RAZORPAY", "REQUIRES_PAYMENT", "INR"));
    String sig = RazorpayGateway.hmacHex("secret", "order_9|pay_1");

    assertThatThrownBy(() -> service.verifyRazorpay(paymentId, user, "order_9", "pay_1", "forged"))
        .isInstanceOf(CodedException.class);
    assertThatThrownBy(
            () -> service.verifyRazorpay(paymentId, UUID.randomUUID(), "order_9", "pay_1", sig))
        .isInstanceOf(CodedException.class);
    assertThatThrownBy(() -> service.verifyRazorpay(paymentId, user, "order_X", "pay_1", sig))
        .isInstanceOf(CodedException.class);
    verify(repo, never()).applyOutcome(any(), anyString());
  }

  @Test
  @DisplayName("webhook: captured payment with matching amount settles the order")
  void webhookCaptured() {
    stubFind(payment("RAZORPAY", "REQUIRES_PAYMENT", "INR"));
    when(repo.eventProcessed(anyString())).thenReturn(false);
    String body = captured("order_9", 49900, "INR");

    service.handleRazorpayWebhook(body, sign(body), "evt_1");

    verify(repo).applyOutcome(paymentId, "SUCCEEDED");
    verify(repo).recordEvent("rzp:evt_1");
  }

  @Test
  @DisplayName("webhook: bad signature is rejected before anything is read")
  void webhookBadSignature() {
    String body = captured("order_9", 49900, "INR");

    assertThatThrownBy(() -> service.handleRazorpayWebhook(body, "nope", "evt_1"))
        .isInstanceOf(CodedException.class);
    verify(repo, never()).findByProviderOrderId(anyString());
  }

  @Test
  @DisplayName("webhook: a duplicate event is ignored")
  void webhookDuplicate() {
    when(repo.eventProcessed("rzp:evt_1")).thenReturn(true);
    String body = captured("order_9", 49900, "INR");

    service.handleRazorpayWebhook(body, sign(body), "evt_1");

    verify(repo, never()).applyOutcome(any(), anyString());
  }

  @Test
  @DisplayName("webhook: an amount we did not ask for is never applied")
  void webhookAmountMismatch() {
    stubFind(payment("RAZORPAY", "REQUIRES_PAYMENT", "INR"));
    String body = captured("order_9", 100, "INR");

    service.handleRazorpayWebhook(body, sign(body), "evt_2");

    verify(repo, never()).applyOutcome(any(), anyString());
  }

  @Test
  @DisplayName("webhook: failed attempts are ignored so the customer can retry")
  void webhookFailedIgnored() {
    String body =
        "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\","
            + "\"order_id\":\"order_9\",\"amount\":49900,\"currency\":\"INR\"}}}}";

    service.handleRazorpayWebhook(body, sign(body), "evt_3");

    verify(repo, never()).applyOutcome(any(), anyString());
  }

  @Test
  @DisplayName("switching Stripe to Razorpay creates the order and cancels the intent")
  void switchStripeToRazorpay() {
    props.getStripe().setSecretKey("sk_test");
    when(stripe.enabled()).thenReturn(true);
    PaymentProviders providers = new PaymentProviders(props, new MockEnvironment());
    service =
        new PaymentService(
            repo, stripe, razorpay, providers, reporter, mock(WalletService.class), props, tx);
    Payment onStripe =
        new Payment(
            paymentId,
            subject,
            user,
            "CARD",
            "STRIPE",
            new BigDecimal("499.00"),
            "INR",
            "REQUIRES_PAYMENT",
            "pi_1",
            BigDecimal.ZERO,
            false,
            "ORDER",
            subject,
            null,
            null);
    when(repo.findByOrderId(subject)).thenReturn(Optional.of(onStripe));
    when(repo.findById(paymentId)).thenReturn(Optional.of(onStripe));
    org.mockito.Mockito.doReturn(new RazorpayGateway.Order("order_new", "created", 49900, "INR"))
        .when(razorpay)
        .createOrder(anyString(), eq(49900L), eq("INR"));
    when(repo.switchProvider(paymentId, "RAZORPAY", null, "order_new")).thenReturn(1);

    service.switchProvider(subject, user, "RAZORPAY");

    verify(stripe).cancel("pi_1");
  }

  @Test
  @DisplayName("switching is refused once the payment is no longer awaiting money")
  void switchRefusedWhenPaid() {
    when(repo.findByOrderId(subject))
        .thenReturn(Optional.of(payment("RAZORPAY", "SUCCEEDED", "INR")));

    assertThatThrownBy(() -> service.switchProvider(subject, user, "STRIPE"))
        .isInstanceOf(CodedException.class);
  }

  @Test
  @DisplayName("refund on Razorpay finds the captured payment and refunds paise")
  void refundRazorpay() {
    Payment paid = payment("RAZORPAY", "SUCCEEDED", "INR");
    stubFind(paid);
    when(repo.refundExists("k1")).thenReturn(false);
    when(repo.lockById(paymentId)).thenReturn(Optional.of(paid));
    org.mockito.Mockito.doReturn(new RazorpayGateway.CapturedPayment("pay_1", 49900, "INR"))
        .when(razorpay)
        .capturedPayment("order_9");
    org.mockito.Mockito.doReturn("rfnd_1")
        .when(razorpay)
        .refund(eq("pay_1"), eq(10000L), eq("k1"), any());
    when(tx.execute(any()))
        .thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));

    var res =
        service.refund(
            subject, new RefundRequest(new BigDecimal("100.00"), "damaged", "k1", "SOURCE"));

    assertThat(res.status()).isEqualTo("PARTIALLY_REFUNDED");
    ArgumentCaptor<String> providerRefund = ArgumentCaptor.forClass(String.class);
    verify(repo)
        .insertRefund(eq(paymentId), any(), any(), eq(null), providerRefund.capture(), eq("k1"));
    assertThat(providerRefund.getValue()).isEqualTo("rfnd_1");
  }

  // ---- owner read: throttled, single-call provider re-check -----------------------------------

  private final long[] now = {1_000_000L};

  private void useFakeClock() {
    service.clock = () -> now[0];
  }

  @Test
  @DisplayName("re-check calls Razorpay once per interval, not on every page load")
  void recheckIsThrottled() {
    useFakeClock();
    stubFind(payment("RAZORPAY", "REQUIRES_PAYMENT", "INR"));
    org.mockito.Mockito.doReturn(null).when(razorpay).capturedPayment("order_9");

    service.getForOwner(subject, user);
    service.getForOwner(subject, user);
    now[0] += PaymentService.RECHECK_INTERVAL_MS - 1;
    service.getForOwner(subject, user);
    verify(razorpay, org.mockito.Mockito.times(1)).capturedPayment("order_9");

    now[0] += 1;
    service.getForOwner(subject, user);
    verify(razorpay, org.mockito.Mockito.times(2)).capturedPayment("order_9");
    verify(razorpay, never()).fetchOrder(anyString());
  }

  @Test
  @DisplayName("a just-created Razorpay order is not re-checked (it cannot be paid yet)")
  void freshOrderSkipsRecheck() {
    useFakeClock();
    org.mockito.Mockito.doReturn(new RazorpayGateway.Order("order_9", "created", 49900, "INR"))
        .when(razorpay)
        .createOrder(anyString(), eq(49900L), eq("INR"));
    when(repo.findByOrderId(subject))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(payment("RAZORPAY", "REQUIRES_PAYMENT", "INR")));
    service.create(
        new CreatePaymentRequest(
            subject, user, new BigDecimal("499"), "INR", "CARD", null, null, null));

    service.getForOwner(subject, user);

    verify(razorpay, never()).capturedPayment(anyString());
  }

  @Test
  @DisplayName("re-check settles on a captured payment of the right amount, with one API call")
  void recheckSettlesOnCapture() {
    stubFind(payment("RAZORPAY", "REQUIRES_PAYMENT", "INR"));
    org.mockito.Mockito.doReturn(new RazorpayGateway.CapturedPayment("pay_7", 49900, "INR"))
        .when(razorpay)
        .capturedPayment("order_9");

    service.getForOwner(subject, user);

    verify(repo).setProviderPaymentId(paymentId, "pay_7");
    verify(repo).applyOutcome(paymentId, "SUCCEEDED");
    verify(razorpay, never()).fetchOrder(anyString());
  }

  @Test
  @DisplayName("re-check never settles on an amount we did not ask for")
  void recheckRejectsAmountMismatch() {
    stubFind(payment("RAZORPAY", "REQUIRES_PAYMENT", "INR"));
    org.mockito.Mockito.doReturn(new RazorpayGateway.CapturedPayment("pay_7", 100, "INR"))
        .when(razorpay)
        .capturedPayment("order_9");

    service.getForOwner(subject, user);

    verify(repo, never()).applyOutcome(any(), anyString());
    verify(repo, never()).setProviderPaymentId(any(), anyString());
  }
}
