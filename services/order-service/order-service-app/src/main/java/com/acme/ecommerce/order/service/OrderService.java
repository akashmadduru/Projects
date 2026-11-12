package com.acme.ecommerce.order.service;

import com.acme.ecommerce.cart.api.dto.CartItemResponse;
import com.acme.ecommerce.cart.api.dto.CartResponse;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.DomainRuleViolationException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.DeliveryEstimateResponse;
import com.acme.ecommerce.order.api.dto.OrderLineRequest;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.PaymentBlockResponse;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest;
import com.acme.ecommerce.order.client.CartFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentResult;
import com.acme.ecommerce.order.client.ProductFeignClient;
import com.acme.ecommerce.order.client.PromotionFeignClient.Validation;
import com.acme.ecommerce.order.client.RemoteCalls;
import com.acme.ecommerce.order.client.UserFeignClient;
import com.acme.ecommerce.order.domain.HistoryActor;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.Pricing;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import feign.FeignException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Checkout orchestration: cart, address and product lookups are remote calls made on the REQUEST
 * thread (so the relayed bearer token is available), sequentially, with no database transaction
 * open. Only the inserts/updates are transactional, in {@link OrderStore}.
 *
 * <p>Prices are re-read from product-service (sale price when positive, else list price); the
 * display prices stored in the cart are never trusted.
 */
@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);
  private static final int MAX_LINES = 50;
  private static final int MAX_QTY = 99;

  /**
   * Outcome of a placement: the order and whether it was newly created (vs an idempotent replay).
   */
  public record Placement(OrderResponse order, boolean created) {}

  private record Line(UUID productId, int quantity) {}

  private final OrderStore store;
  private final CartFeignClient carts;
  private final UserFeignClient users;
  private final ProductFeignClient products;
  private final PricingService pricing;
  private final OrderRemote remote;
  private final OrderEventPublisher events;

  public OrderService(
      OrderStore store,
      CartFeignClient carts,
      UserFeignClient users,
      ProductFeignClient products,
      PricingService pricing,
      OrderRemote remote,
      OrderEventPublisher events) {
    this.store = store;
    this.carts = carts;
    this.users = users;
    this.products = products;
    this.pricing = pricing;
    this.remote = remote;
    this.events = events;
  }

  public Placement place(UUID userId, String email, String idempotencyKey, PlaceOrderRequest req) {
    Optional<OrderResponse> replay = store.findReplay(userId, idempotencyKey, req.addressId());
    if (replay.isPresent()) {
      return new Placement(refreshPayment(replay.get()), false);
    }

    boolean buyNow = req.items() != null && !req.items().isEmpty();
    List<Line> lines = buyNow ? buyNowLines(req.items()) : cartLines();
    PaymentMethod method = PaymentMethod.valueOf(req.paymentMethod().name());

    AddressResponse address = fetchAddress(req.addressId());
    Order order = draft(userId, email, idempotencyKey, address, lines, method);

    String couponCode =
        req.couponCode() == null || req.couponCode().isBlank()
            ? null
            : req.couponCode().trim().toUpperCase(Locale.ROOT);
    BigDecimal discount = BigDecimal.ZERO;
    if (couponCode != null) {
      Validation v = remote.validateCoupon(
              couponCode, order.getSubtotalAmount(), order.getCurrency(), store.isFirstOrder(userId));
      discount = v.discountAmount() == null ? BigDecimal.ZERO : v.discountAmount();
    }

    Pricing price =
        pricing.price(order.getSubtotalAmount(), discount, order.getCurrency(), address.state());
    DeliveryEstimateResponse estimate =
        pricing.estimate(address.postalCode(), price.total(), order.getCurrency());
    if (method == PaymentMethod.COD && !pricing.codAllowed(estimate, price.total(), order.getCurrency())) {
      throw new CodedException(
          ErrorCode.PAYMENT_METHOD_UNAVAILABLE,
          "Cash on delivery is not available for this address or order value");
    }

    boolean firstOrder = store.isFirstOrder(userId);
    order.begin(price, couponCode, estimate.estimatedDeliveryDate(), Instant.now());

    OrderResponse saved;
    try {
      saved = store.save(order);
    } catch (DataIntegrityViolationException race) {
      // A concurrent request with the same key won the unique constraint: return its order.
      Optional<OrderResponse> winner = store.findReplay(userId, idempotencyKey, req.addressId());
      if (winner.isEmpty()) {
        throw race;
      }
      return new Placement(winner.get(), false);
    }

    List<ReturnLine> stockLines =
        lines.stream().map(l -> new ReturnLine(l.productId(), l.quantity())).toList();

    try {
      remote.reserveStock(saved.id(), stockLines);
    } catch (RuntimeException e) {
      abandon(saved.id(), "Could not reserve stock", false);
      throw e;
    }

    if (couponCode != null) {
      try {
        remote.redeemCoupon(
            couponCode, userId, saved.id(), saved.subtotalAmount(), saved.currency(), firstOrder);
      } catch (RuntimeException e) {
        abandon(saved.id(), "Coupon could not be applied", false);
        throw e;
      }
    }

    PaymentBlockResponse paymentBlock = null;
    try {
      PaymentResult p =
          remote.createPayment(
              saved.id(), userId, saved.totalAmount(), saved.currency(), method.name());
      store.attachPayment(saved.id(), p.paymentId());
      if (method == PaymentMethod.CARD) {
        paymentBlock = toBlock(p);
      }
    } catch (RuntimeException e) {
      if (method == PaymentMethod.CARD) {
        abandon(saved.id(), "Payment could not be started", couponCode != null);
        throw e;
      }
      log.warn("COD order {} placed but payment record not created: {}", saved.id(), e.toString());
    }

    if (!buyNow) {
      clearCartBestEffort(saved.id());
    }
    if (method == PaymentMethod.COD) {
      events.publish(OrderEventType.ORDER_PLACED, saved);
    }
    return new Placement(paymentBlock == null ? saved : saved.withPayment(paymentBlock), true);
  }

  /** Replays of a still-pending CARD order carry a fresh payment block (create is idempotent). */
  private OrderResponse refreshPayment(OrderResponse order) {
    if (!"CARD".equals(order.paymentMethod()) || !"PENDING_PAYMENT".equals(order.status())) {
      return order;
    }
    try {
      PaymentResult p =
          remote.createPayment(
              order.id(), order.userId(), order.totalAmount(), order.currency(), "CARD");
      return order.withPayment(toBlock(p));
    } catch (RuntimeException e) {
      log.warn("Could not refresh payment for replayed order {}: {}", order.id(), e.toString());
      return order;
    }
  }

  /** Compensation for a placement that failed after the order row was written. */
  private void abandon(UUID orderId, String note, boolean releaseCoupon) {
    try {
      store.systemCancel(orderId, note);
    } catch (RuntimeException e) {
      log.error("Could not cancel abandoned order {}: {}", orderId, e.toString());
    }
    remote.restoreStockBestEffort(orderId);
    if (releaseCoupon) {
      remote.releaseCouponBestEffort(orderId);
    }
  }

  private static PaymentBlockResponse toBlock(PaymentResult p) {
    return new PaymentBlockResponse(
        p.paymentId(), p.status(), p.mode(), p.clientSecret(), p.publishableKey());
  }

  private List<Line> cartLines() {
    CartResponse cart = call("cart-service", carts::getCart);
    if (cart == null || cart.items() == null || cart.items().isEmpty()) {
      throw new CodedException(ErrorCode.CART_EMPTY, "Your cart is empty");
    }
    if (cart.items().size() > MAX_LINES) {
      throw new DomainRuleViolationException(
          "An order can have at most " + MAX_LINES + " distinct products");
    }
    List<Line> lines = new ArrayList<>();
    for (CartItemResponse i : cart.items()) {
      lines.add(new Line(i.productId(), i.quantity()));
    }
    return lines;
  }

  private static List<Line> buyNowLines(List<OrderLineRequest> items) {
    Map<UUID, Integer> merged = new LinkedHashMap<>();
    for (OrderLineRequest i : items) {
      merged.merge(i.productId(), i.quantity(), Integer::sum);
    }
    if (merged.size() > MAX_LINES) {
      throw new DomainRuleViolationException(
          "An order can have at most " + MAX_LINES + " distinct products");
    }
    List<Line> lines = new ArrayList<>();
    for (Map.Entry<UUID, Integer> e : merged.entrySet()) {
      if (e.getValue() > MAX_QTY) {
        throw new DomainRuleViolationException("Quantity per product is limited to " + MAX_QTY);
      }
      lines.add(new Line(e.getKey(), e.getValue()));
    }
    return lines;
  }

  private Order draft(
      UUID userId,
      String email,
      String key,
      AddressResponse address,
      List<Line> lines,
      PaymentMethod method) {
    Order order = null;
    String currency = null;
    for (Line line : lines) {
      ProductResponse product = fetchProduct(line.productId());
      if (!product.isActive() || unitPrice(product) == null) {
        throw new CodedException(
            ErrorCode.PRODUCT_UNAVAILABLE, "Product is no longer available: " + product.getName());
      }
      String lineCurrency = product.getPriceCurrency();
      if (currency == null) {
        currency = lineCurrency;
        order =
            Order.draft(
                userId,
                email,
                key,
                address.id(),
                new Order.ShipTo(
                    address.fullName(),
                    address.phone(),
                    address.line1(),
                    address.line2(),
                    address.city(),
                    address.state(),
                    address.postalCode(),
                    address.country()),
                currency,
                method,
                Instant.now());
      } else if (!currency.equals(lineCurrency)) {
        throw new CodedException(
            ErrorCode.CURRENCY_MISMATCH, "Products in the order use different currencies");
      }
      order.addItem(
          product.getId(),
          product.getSku(),
          product.getName(),
          unitPrice(product),
          line.quantity(),
          thumbnailOrNull(product.getThumbnailUrl()));
    }
    return order;
  }

  /** Sale price when set and positive, else the list price. */
  static BigDecimal unitPrice(ProductResponse p) {
    BigDecimal sale = p.getSalePrice();
    if (sale != null && sale.signum() > 0) {
      return sale;
    }
    return p.getPriceAmount();
  }

  private AddressResponse fetchAddress(UUID addressId) {
    try {
      return users.findAddress(addressId);
    } catch (FeignException.NotFound e) {
      throw new CodedException(ErrorCode.ADDRESS_NOT_FOUND, "Address not found");
    } catch (FeignException e) {
      throw RemoteCalls.unavailable("user-service", e);
    }
  }

  private ProductResponse fetchProduct(UUID productId) {
    try {
      return products.findById(productId);
    } catch (FeignException.NotFound e) {
      throw new CodedException(
          ErrorCode.PRODUCT_UNAVAILABLE, "A product in your order no longer exists");
    } catch (FeignException e) {
      throw RemoteCalls.unavailable("product-service", e);
    }
  }

  private void clearCartBestEffort(UUID orderId) {
    try {
      carts.clearCart();
    } catch (RuntimeException e) {
      log.warn("Order {} placed but the cart could not be cleared: {}", orderId, e.toString());
    }
  }

  private static <T> T call(String service, java.util.function.Supplier<T> action) {
    try {
      return action.get();
    } catch (FeignException e) {
      throw RemoteCalls.unavailable(service, e);
    }
  }

  /** Only the catalog thumbnail is trusted; the cart's client-supplied imageUrl is never copied. */
  private static String thumbnailOrNull(String thumbnail) {
    return thumbnail != null && !thumbnail.isBlank() ? thumbnail : null;
  }
}
