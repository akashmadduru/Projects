package com.acme.ecommerce.order.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.RefundResult;
import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.client.SellerInternalFeignClient;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.repository.OrderRepository;
import com.acme.ecommerce.order.service.OrderLifecycleService;
import com.acme.ecommerce.order.service.OrderStore;
import com.acme.ecommerce.order.service.WalletCreditBacks;
import com.acme.ecommerce.order.support.CancelFixture.Built;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Shared wiring for the F-14 cancel ITs (one Spring context for all of them). */
public abstract class AbstractCancelIT extends AbstractOrderIntegrationTest {

  @MockitoBean protected PaymentFeignClient payments;
  @MockitoBean protected PromotionFeignClient promotions;
  @MockitoBean protected SellerInternalFeignClient sellers;
  @MockitoSpyBean protected OrderEventPublisher events;

  @Autowired protected OrderLifecycleService lifecycle;
  @Autowired protected OrderStore store;
  @Autowired protected OrderRepository orders;
  @Autowired protected WalletCreditBacks walletCredits;
  @Autowired protected JdbcTemplate jdbc;

  @BeforeEach
  void resetCancelMocks() {
    reset(payments, sellers);
    clearInvocations(events);
    when(payments.refund(any(), any()))
        .thenReturn(new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, "SUCCEEDED"));
  }

  /** What Feign throws when payment-service cannot be reached. */
  protected static FeignException down() {
    Request request =
        Request.create(
            Request.HttpMethod.POST,
            "http://payment-service/x",
            Map.of(),
            null,
            StandardCharsets.UTF_8,
            null);
    return FeignException.errorStatus(
        "refund",
        Response.builder().status(503).reason("down").request(request).headers(Map.of()).build());
  }

  protected Built build(
      PaymentMethod method,
      String discount,
      String coins,
      String shipping,
      String wallet,
      String... amounts) {
    return CancelFixture.build(orders, jdbc, method, discount, coins, shipping, wallet, amounts);
  }

  /** The 850/510/390 example of the design: S1 1000, S2 600, S3 400, total 1750, wallet 350. */
  protected Built workedExample() {
    return build(
        PaymentMethod.CARD, "200.00", "100.00", "50.00", "350.00", "1000.00", "600.00", "400.00");
  }

  protected String cancelState(UUID shipmentId) {
    return jdbc.queryForObject(
        "SELECT cancel_state FROM orders.order_shipments WHERE id = ?", String.class, shipmentId);
  }

  protected String shipmentStatus(UUID shipmentId) {
    return jdbc.queryForObject(
        "SELECT status FROM orders.order_shipments WHERE id = ?", String.class, shipmentId);
  }

  protected String orderStatus(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT status FROM orders.orders WHERE id = ?", String.class, orderId);
  }

  protected String paymentStatus(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT payment_status FROM orders.orders WHERE id = ?", String.class, orderId);
  }

  protected BigDecimal refunded(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT refunded_amount FROM orders.orders WHERE id = ?", BigDecimal.class, orderId);
  }

  protected int walletRows(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM orders.pending_wallet_credits WHERE order_id = ?",
        Integer.class,
        orderId);
  }

  protected BigDecimal walletRowAmount(UUID shipmentId) {
    return jdbc.queryForObject(
        "SELECT amount FROM orders.pending_wallet_credits WHERE idem_key = ?",
        BigDecimal.class,
        "ship-cancel-wallet-" + shipmentId);
  }

  protected int outboxRows(UUID orderId, String kind) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM orders.outbox WHERE aggregate_id = ? AND kind = ?",
        Integer.class,
        orderId,
        kind);
  }

  protected int releaseRows(UUID orderId, UUID shipmentId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM orders.outbox WHERE idem_key = ?",
        Integer.class,
        "RELEASE_STOCK:" + orderId + ":" + shipmentId);
  }

  protected void cancel(Built b, int index) {
    lifecycle.adminCancelShipment(b.orderId(), b.shipmentIds().get(index), "no stock");
  }
}
