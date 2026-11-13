package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.web.job.JobLock;
import org.springframework.beans.factory.annotation.Autowired;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.order.api.dto.OrderLineRequest;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest.PaymentMethodRequest;
import com.acme.ecommerce.order.client.ProductViewFeignClient;
import com.acme.ecommerce.order.client.ProductViewFeignClient.ProductView;
import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import feign.FeignException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Subscribe and Save: recurring orders placed through the normal placement path by a daily job. */
@Service
public class SubscriptionService {

  private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
  private static final Set<Integer> FREQUENCIES = Set.of(7, 14, 30, 60);

  public record Subscription(
      UUID id,
      UUID userId,
      String email,
      UUID productId,
      UUID variantId,
      String productName,
      int quantity,
      int frequencyDays,
      LocalDate nextRunOn,
      UUID addressId,
      String paymentMethod,
      String status,
      int discountPercent,
      UUID lastOrderId,
      int failures,
      OffsetDateTime createdAt) {}

  public record CreateRequest(
      UUID productId,
      UUID variantId,
      int quantity,
      int frequencyDays,
      UUID addressId,
      String paymentMethod) {}

  public record UpdateRequest(
      Integer quantity,
      Integer frequencyDays,
      UUID addressId,
      String paymentMethod,
      String status) {}

  public record RunSummary(LocalDate date, int due, int placed, int failed) {}

  private static final String COLS =
      "id, user_id, email, product_id, variant_id, product_name, quantity, frequency_days, "
          + "next_run_on, address_id, payment_method, status, discount_percent, last_order_id, "
          + "failures, created_at";

  private final JdbcClient jdbc;
  private final OrderService orders;
  private final OrderRemote remote;
  private final ProductViewFeignClient products;
  private final OrderEventPublisher events;
  private final PricingService pricing;
  private final OrderProperties props;

  public SubscriptionService(
      JdbcClient jdbc,
      OrderService orders,
      OrderRemote remote,
      ProductViewFeignClient products,
      OrderEventPublisher events,
      PricingService pricing,
      OrderProperties props) {
    this.jdbc = jdbc;
    this.orders = orders;
    this.remote = remote;
    this.products = products;
    this.events = events;
    this.pricing = pricing;
    this.props = props;
  }

  private static Subscription map(ResultSet rs, int n) throws SQLException {
    return new Subscription(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("email"),
        rs.getObject("product_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("product_name"),
        rs.getInt("quantity"),
        rs.getInt("frequency_days"),
        rs.getObject("next_run_on", LocalDate.class),
        rs.getObject("address_id", UUID.class),
        rs.getString("payment_method"),
        rs.getString("status"),
        rs.getInt("discount_percent"),
        rs.getObject("last_order_id", UUID.class),
        rs.getInt("failures"),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  public List<Subscription> list(UUID userId) {
    return jdbc.sql(
            "SELECT "
                + COLS
                + " FROM orders.subscriptions WHERE user_id = :u AND status <> 'CANCELLED'"
                + " ORDER BY created_at DESC")
        .param("u", userId)
        .query(SubscriptionService::map)
        .list();
  }

  public Subscription create(UUID userId, String email, CreateRequest r) {
    validate(r.quantity(), r.frequencyDays(), r.paymentMethod());
    ProductView p;
    try {
      p = products.findById(r.productId());
    } catch (FeignException e) {
      throw new CodedException(ErrorCode.PRODUCT_UNAVAILABLE, "Product is not available");
    }
    if (!p.active()) {
      throw new CodedException(ErrorCode.PRODUCT_UNAVAILABLE, "Product is not available");
    }
    boolean hasVariants =
        p.variants() != null && p.variants().stream().anyMatch(v -> !Boolean.FALSE.equals(v.active()));
    if (hasVariants && r.variantId() == null) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Please choose a variant");
    }
    LocalDate next = pricing.today().plusDays(r.frequencyDays());
    UUID id =
        jdbc.sql(
                "INSERT INTO orders.subscriptions (user_id, email, product_id, variant_id, "
                    + "product_name, quantity, frequency_days, next_run_on, address_id, "
                    + "payment_method, status, discount_percent) VALUES (:u, :e, :p, :v, :n, :q, "
                    + ":f, :next, :a, :m, 'ACTIVE', :d) RETURNING id")
            .param("u", userId)
            .param("e", email)
            .param("p", r.productId())
            .param("v", r.variantId())
            .param("n", p.name())
            .param("q", r.quantity())
            .param("f", r.frequencyDays())
            .param("next", next)
            .param("a", r.addressId())
            .param("m", r.paymentMethod())
            .param("d", props.getLoyalty().getSubscriptionDiscountPercent())
            .query(UUID.class)
            .single();
    return get(userId, id);
  }

  public Subscription get(UUID userId, UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM orders.subscriptions WHERE id = :i AND user_id = :u")
        .param("i", id)
        .param("u", userId)
        .query(SubscriptionService::map)
        .optional()
        .orElseThrow(() -> new ResourceNotFoundException("Subscription", id));
  }

  public Subscription update(UUID userId, UUID id, UpdateRequest r) {
    Subscription s = get(userId, id);
    int quantity = r.quantity() != null ? r.quantity() : s.quantity();
    int freq = r.frequencyDays() != null ? r.frequencyDays() : s.frequencyDays();
    String method = r.paymentMethod() != null ? r.paymentMethod() : s.paymentMethod();
    UUID address = r.addressId() != null ? r.addressId() : s.addressId();
    String status = r.status() != null ? r.status().toUpperCase() : s.status();
    validate(quantity, freq, method);
    if (!Set.of("ACTIVE", "PAUSED", "CANCELLED").contains(status)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Unknown status " + status);
    }
    if ("CANCELLED".equals(s.status())) {
      throw new CodedException(ErrorCode.RESOURCE_CONFLICT, "Subscription is cancelled");
    }
    LocalDate next = s.nextRunOn();
    int failures = s.failures();
    if ("ACTIVE".equals(status) && !"ACTIVE".equals(s.status())) {
      failures = 0;
      if (next.isBefore(pricing.today())) {
        next = pricing.today().plusDays(1);
      }
    }
    if (r.frequencyDays() != null && freq != s.frequencyDays()) {
      next = pricing.today().plusDays(freq);
    }
    jdbc.sql(
            "UPDATE orders.subscriptions SET quantity = :q, frequency_days = :f, address_id = :a,"
                + " payment_method = :m, status = :st, next_run_on = :n, failures = :fl"
                + " WHERE id = :i AND user_id = :u")
        .param("q", quantity)
        .param("f", freq)
        .param("a", address)
        .param("m", method)
        .param("st", status)
        .param("n", next)
        .param("fl", failures)
        .param("i", id)
        .param("u", userId)
        .update();
    return get(userId, id);
  }

  public Subscription skip(UUID userId, UUID id) {
    Subscription s = get(userId, id);
    jdbc.sql(
            "UPDATE orders.subscriptions SET next_run_on = next_run_on + :d "
                + "WHERE id = :i AND user_id = :u AND status <> 'CANCELLED'")
        .param("d", s.frequencyDays())
        .param("i", id)
        .param("u", userId)
        .update();
    return get(userId, id);
  }

  private static void validate(int quantity, int freq, String method) {
    if (quantity < 1 || quantity > 99) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "quantity must be between 1 and 99");
    }
    if (!FREQUENCIES.contains(freq)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "frequencyDays must be 7, 14, 30 or 60");
    }
    if (!"COD".equals(method) && !"WALLET".equals(method)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "paymentMethod must be COD or WALLET");
    }
  }

  // ---- job -----------------------------------------------------------------------------------

  /** Cluster-wide guard so only one replica runs this job per tick. */
  @Autowired private JobLock jobLock;

  @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
  public void dailyJob() {
    jobLock.runExclusive("order-subscriptions-daily", this::dailyJobLocked);
  }

  private void dailyJobLocked() {
    try {
      RunSummary s = runDue(pricing.today());
      log.info("Subscription run: {}", s);
    } catch (RuntimeException e) {
      log.error("Subscription run failed: {}", e.toString());
    }
  }

  /** Places an order for every ACTIVE subscription due on or before {@code date}. */
  public RunSummary runDue(LocalDate date) {
    List<Subscription> due =
        jdbc.sql(
                "SELECT "
                    + COLS
                    + " FROM orders.subscriptions WHERE status = 'ACTIVE' AND next_run_on <= :d"
                    + " ORDER BY next_run_on LIMIT 500")
            .param("d", date)
            .query(SubscriptionService::map)
            .list();
    int placed = 0;
    int failed = 0;
    for (Subscription s : due) {
      try {
        runOne(s, date);
        placed++;
      } catch (RuntimeException e) {
        failed++;
        fail(s, e);
      }
    }
    return new RunSummary(date, due.size(), placed, failed);
  }

  private void runOne(Subscription s, LocalDate date) {
    AddressResponse address = remote.addressFor(s.userId(), s.addressId());
    PlaceOrderRequest req =
        new PlaceOrderRequest(
            s.addressId(),
            PaymentMethodRequest.valueOf(s.paymentMethod()),
            null,
            List.of(new OrderLineRequest(s.productId(), s.quantity(), s.variantId(), null)),
            null,
            null);
    OrderResponse order =
        orders
            .place(
                s.userId(),
                s.email(),
                "sub-" + s.id() + "-" + date,
                req,
                address,
                s.discountPercent(),
                s.id())
            .order();
    jdbc.sql(
            "UPDATE orders.subscriptions SET last_order_id = :o, failures = 0, next_run_on = :n"
                + " WHERE id = :i")
        .param("o", order.id())
        .param("n", date.plusDays(s.frequencyDays()))
        .param("i", s.id())
        .update();
    log.info("Subscription {} placed order {}", s.id(), order.id());
  }

  private void fail(Subscription s, RuntimeException e) {
    int failures = s.failures() + 1;
    boolean pause = failures >= props.getLoyalty().getSubscriptionMaxFailures();
    log.warn("Subscription {} failed ({}): {}", s.id(), failures, e.toString());
    jdbc.sql(
            "UPDATE orders.subscriptions SET failures = :f, status = :st WHERE id = :i")
        .param("f", failures)
        .param("st", pause ? "PAUSED" : "ACTIVE")
        .param("i", s.id())
        .update();
    if (!pause) {
      jdbc.sql("UPDATE orders.subscriptions SET next_run_on = :n WHERE id = :i")
          .param("n", pricing.today().plusDays(1).isAfter(s.nextRunOn()) ? pricing.today().plusDays(1) : s.nextRunOn().plusDays(1))
          .param("i", s.id())
          .update();
    }
    events.publishSubscriptionFailed(
        s.id(), s.userId(), s.email(), s.productName(), failures, pause);
  }
}
