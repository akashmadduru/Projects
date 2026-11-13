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
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentResult;
import com.acme.ecommerce.order.client.ProductViewFeignClient;
import com.acme.ecommerce.order.client.ProductViewFeignClient.Offer;
import com.acme.ecommerce.order.client.ProductViewFeignClient.ProductView;
import com.acme.ecommerce.order.client.ProductViewFeignClient.Variant;
import com.acme.ecommerce.order.client.PromotionFeignClient;
import com.acme.ecommerce.order.client.PromotionFeignClient.DealClaimItem;
import com.acme.ecommerce.order.error.RiskBlockedException;
import com.acme.ecommerce.order.client.PromotionFeignClient.DealKey;
import com.acme.ecommerce.order.client.PromotionFeignClient.DealPrice;
import com.acme.ecommerce.order.client.PromotionFeignClient.Validation;
import com.acme.ecommerce.order.client.RemoteCalls;
import com.acme.ecommerce.order.client.UserFeignClient;
import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.domain.HistoryActor;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.Order.Allocation;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.Pricing;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import feign.FeignException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

  private record Line(
      UUID productId, UUID variantId, String variantLabel, int quantity, UUID offerId) {}

  private final OrderStore store;
  private final CartFeignClient carts;
  private final UserFeignClient users;
  private final ProductViewFeignClient products;
  private final PricingService pricing;
  private final OrderRemote remote;
  private final OrderEventPublisher events;
  private final OrderProperties props;
  private final RiskService risks;

  public OrderService(
      OrderStore store,
      CartFeignClient carts,
      UserFeignClient users,
      ProductViewFeignClient products,
      PricingService pricing,
      OrderRemote remote,
      OrderEventPublisher events,
      OrderProperties props,
      RiskService risks) {
    this.risks = risks;
    this.store = store;
    this.carts = carts;
    this.users = users;
    this.products = products;
    this.pricing = pricing;
    this.remote = remote;
    this.events = events;
    this.props = props;
  }

  public Placement place(UUID userId, String email, String idempotencyKey, PlaceOrderRequest req) {
    return place(userId, email, idempotencyKey, req, null, 0, null);
  }

  /**
   * Core placement. The last three arguments are for the Subscribe and Save job (no user JWT: the
   * address is supplied, a line discount is applied and the order is tagged with the subscription).
   */
  public Placement place(
      UUID userId,
      String email,
      String idempotencyKey,
      PlaceOrderRequest req,
      AddressResponse addressOverride,
      int lineDiscountPercent,
      UUID subscriptionId) {
    Optional<OrderResponse> replay = store.findReplay(userId, idempotencyKey, req.addressId());
    if (replay.isPresent()) {
      return new Placement(refreshPayment(replay.get()), false);
    }

    boolean buyNow = req.items() != null && !req.items().isEmpty();
    List<Line> lines = buyNow ? buyNowLines(req.items()) : cartLines();
    PaymentMethod method = PaymentMethod.valueOf(req.paymentMethod().name());

    AddressResponse address =
        addressOverride != null ? addressOverride : fetchAddress(req.addressId());
    List<DealClaimItem> dealClaims = new ArrayList<>();
    Map<UUID, String> sellerNames = new LinkedHashMap<>();
    Order order =
        draft(
            userId,
            email,
            idempotencyKey,
            address,
            lines,
            method,
            dealClaims,
            sellerNames,
            lineDiscountPercent);
    String currency = order.getCurrency();

    String couponCode =
        req.couponCode() == null || req.couponCode().isBlank()
            ? null
            : req.couponCode().trim().toUpperCase(Locale.ROOT);
    BigDecimal discount = BigDecimal.ZERO;
    if (couponCode != null) {
      Validation v = remote.validateCoupon(
              couponCode, order.getSubtotalAmount(), currency, store.isFirstOrder(userId));
      discount = v.discountAmount() == null ? BigDecimal.ZERO : v.discountAmount();
    }

    // Exchange offer (Phase 10): the quoted device value is an extra discount on the subtotal.
    UUID exchangeQuoteId = req.exchangeQuoteId();
    BigDecimal exchangeValue = BigDecimal.ZERO;
    if (exchangeQuoteId != null) {
      PromotionFeignClient.ExchangeQuoteView quote = remote.exchangeQuote(exchangeQuoteId, userId);
      if (!"QUOTED".equals(quote.status())) {
        throw new CodedException(
            ErrorCode.VALIDATION_FAILED, "This exchange quote is no longer available");
      }
      if (quote.currency() != null && !quote.currency().equalsIgnoreCase(currency)) {
        throw new CodedException(
            ErrorCode.CURRENCY_MISMATCH, "The exchange quote currency does not match the order");
      }
      exchangeValue = quote.quotedValue().min(order.getSubtotalAmount().subtract(discount));
      discount = discount.add(exchangeValue);
    }

    // Coins: at most coinMaxPercent of the subtotal, never more than what is left to pay.
    int coins = req.coinsToRedeem() == null ? 0 : req.coinsToRedeem();
    BigDecimal perCoin = PricingService.isInr(currency) ? BigDecimal.ONE : new BigDecimal("0.01");
    BigDecimal coinsValue = BigDecimal.ZERO;
    if (coins > 0) {
      BigDecimal cap =
          order
              .getSubtotalAmount()
              .multiply(BigDecimal.valueOf(props.getLoyalty().getCoinMaxPercent()))
              .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
      int maxCoins = cap.divide(perCoin, 0, RoundingMode.DOWN).intValue();
      if (coins > maxCoins) {
        throw new DomainRuleViolationException(
            "You can redeem at most " + maxCoins + " coins on this order");
      }
      coinsValue = perCoin.multiply(BigDecimal.valueOf(coins));
    }

    boolean member = remote.isMember(userId);
    Pricing price =
        pricing.price(
            order.getSubtotalAmount(), discount, coinsValue, member, currency, address.state());
    if (coins > 0 && price.coinsValue().compareTo(coinsValue) < 0) {
      coins = price.coinsValue().divide(perCoin, 0, RoundingMode.DOWN).intValue();
    }
    DeliveryEstimateResponse estimate =
        pricing.estimate(address.postalCode(), price.total(), currency);

    BigDecimal walletAmount = BigDecimal.ZERO.setScale(2);
    if ((method == PaymentMethod.WALLET || Boolean.TRUE.equals(req.useWallet()))
        && price.total().signum() > 0) {
      PaymentFeignClient.WalletBalance wb = remote.walletBalance(userId);
      BigDecimal available =
          wb != null && wb.currency() != null && wb.currency().trim().equalsIgnoreCase(currency)
              ? wb.balance()
              : BigDecimal.ZERO;
      if (method == PaymentMethod.WALLET) {
        if (available.compareTo(price.total()) < 0) {
          throw new CodedException(
              ErrorCode.INSUFFICIENT_BALANCE, "Your wallet balance does not cover this order");
        }
        walletAmount = price.total();
      } else {
        walletAmount = available.min(price.total()).setScale(2, RoundingMode.DOWN);
        if (walletAmount.compareTo(price.total()) == 0) {
          method = PaymentMethod.WALLET;
        }
      }
    }
    BigDecimal remainder = price.total().subtract(walletAmount);
    RiskService.Assessment risk =
        subscriptionId != null
            ? new RiskService.Assessment(0, List.of())
            : risks.assess(userId, price.total(), currency);
    if (method == PaymentMethod.COD && remainder.signum() > 0 && risk.blocksCod()) {
      throw new RiskBlockedException(
          "Cash on delivery is not available for this order (" + String.join("; ", risk.reasons()) + ")");
    }
    if (method == PaymentMethod.COD
        && remainder.signum() > 0
        && !pricing.codAllowed(estimate, remainder, currency)) {
      throw new CodedException(
          ErrorCode.PAYMENT_METHOD_UNAVAILABLE,
          "Cash on delivery is not available for this address or order value");
    }

    order.applyRisk(risk.score(), risk.reasons(), risk.hold());
    if (exchangeQuoteId != null) {
      order.applyExchange(exchangeQuoteId, exchangeValue);
    }

    boolean firstOrder = store.isFirstOrder(userId);
    order.switchPaymentMethod(method);
    order.applyMoney(coins, price.coinsValue(), walletAmount, member, subscriptionId);
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

    List<OrderRemote.StockLine> stockLines =
        saved.items().stream()
            .map(i -> new OrderRemote.StockLine(i.productId(), i.variantId(), i.offerId(), i.quantity()))
            .toList();

    List<Allocation> allocations;
    try {
      allocations =
          remote.reserveStock(saved.id(), stockLines, address.postalCode()).stream()
              .map(
                  a ->
                      new Allocation(
                          a.productId(),
                          a.variantId(),
                          a.offerId(),
                          a.warehouseId(),
                          a.warehouseCode(),
                          a.warehousePincode(),
                          a.quantity()))
              .toList();
    } catch (RuntimeException e) {
      abandon(saved.id(), "Could not reserve stock", false);
      throw e;
    }

    try {
      saved =
          store.assignShipments(
              saved.id(),
              allocations,
              sellerNames,
              warehousePin -> pricing.estimateDate(address.postalCode(), warehousePin));
    } catch (RuntimeException e) {
      abandon(saved.id(), "Could not split the order into shipments", false);
      throw e;
    }

    if (!dealClaims.isEmpty()) {
      try {
        remote.claimDeals(saved.id(), dealClaims);
      } catch (RuntimeException e) {
        abandon(saved.id(), "Deal could not be claimed", false);
        throw e;
      }
    }

    if (exchangeQuoteId != null) {
      try {
        remote.applyExchange(exchangeQuoteId, userId, saved.id());
      } catch (RuntimeException e) {
        abandon(saved.id(), "Exchange quote could not be applied", false);
        throw e;
      }
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

    if (coins > 0) {
      try {
        remote.redeemCoins(userId, coins, saved.id());
      } catch (RuntimeException e) {
        abandon(saved.id(), "Coins could not be redeemed", couponCode != null);
        throw e;
      }
    }

    if (walletAmount.signum() > 0) {
      try {
        remote.debitWallet(userId, walletAmount, currency, saved.id());
      } catch (RuntimeException e) {
        unwind(saved, "Wallet could not be charged", couponCode != null, coins > 0, false);
        throw e;
      }
    }

    OrderEventType placedType =
        subscriptionId != null
            ? OrderEventType.SUBSCRIPTION_ORDER_PLACED
            : OrderEventType.ORDER_PLACED;
    PaymentBlockResponse paymentBlock = null;
    if (method == PaymentMethod.WALLET) {
      OrderResponse paid = store.markPaid(saved.id(), null).order();
      events.publish(placedType, paid);
      if (!buyNow) {
        clearCartBestEffort(saved.id());
      }
      return new Placement(paid, true);
    }
    try {
      PaymentResult p =
          remote.createPayment(saved.id(), userId, remainder, saved.currency(), method.name());
      store.attachPayment(saved.id(), p.paymentId());
      if (method == PaymentMethod.CARD) {
        paymentBlock = toBlock(p);
      }
    } catch (RuntimeException e) {
      if (method == PaymentMethod.CARD) {
        unwind(saved, "Payment could not be started", couponCode != null, coins > 0, true);
        throw e;
      }
      log.warn("COD order {} placed but payment record not created: {}", saved.id(), e.toString());
    }

    if (!buyNow) {
      clearCartBestEffort(saved.id());
    }
    if (method == PaymentMethod.COD) {
      events.publish(placedType, saved);
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
              order.id(),
              order.userId(),
              order.totalAmount().subtract(order.walletAmount()),
              order.currency(),
              "CARD");
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

  /** {@link #abandon} plus giving back the coins and wallet amount this order already took. */
  private void unwind(
      OrderResponse saved, String note, boolean releaseCoupon, boolean coins, boolean wallet) {
    abandon(saved.id(), note, releaseCoupon);
    if (coins) {
      remote.releaseCoinsBestEffort(saved.id());
    }
    if (wallet && saved.walletAmount().signum() > 0) {
      remote.creditWalletBackBestEffort(
          saved.userId(), saved.walletAmount(), saved.currency(), saved.id());
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
      lines.add(new Line(i.productId(), i.variantId(), i.variantLabel(), i.quantity(), i.offerId()));
    }
    return lines;
  }


  private static List<Line> buyNowLines(List<OrderLineRequest> items) {
    record Key(UUID productId, UUID variantId, UUID offerId) {}
    Map<Key, Integer> merged = new LinkedHashMap<>();
    for (OrderLineRequest i : items) {
      merged.merge(new Key(i.productId(), i.variantId(), i.offerId()), i.quantity(), Integer::sum);
    }
    if (merged.size() > MAX_LINES) {
      throw new DomainRuleViolationException(
          "An order can have at most " + MAX_LINES + " distinct products");
    }
    List<Line> lines = new ArrayList<>();
    for (Map.Entry<Key, Integer> e : merged.entrySet()) {
      if (e.getValue() > MAX_QTY) {
        throw new DomainRuleViolationException("Quantity per product is limited to " + MAX_QTY);
      }
      lines.add(
          new Line(
              e.getKey().productId(), e.getKey().variantId(), null, e.getValue(), e.getKey().offerId()));
    }
    return lines;
  }

  private Order draft(
      UUID userId,
      String email,
      String key,
      AddressResponse address,
      List<Line> lines,
      PaymentMethod method,
      List<DealClaimItem> dealClaims,
      Map<UUID, String> sellerNames,
      int lineDiscountPercent) {
    List<ProductView> views = new ArrayList<>();
    for (Line line : lines) {
      views.add(fetchProduct(line.productId()));
    }
    List<DealPrice> deals =
        remote.dealPrices(userId, lines.stream().map(l -> new DealKey(l.productId(), l.variantId())).toList());
    Order order = null;
    String currency = null;
    for (int n = 0; n < lines.size(); n++) {
      Line line = lines.get(n);
      ProductView product = views.get(n);
      Variant variant = null;
      boolean hasVariants =
          product.variants() != null
              && product.variants().stream().anyMatch(v -> !Boolean.FALSE.equals(v.active()));
      if (line.variantId() != null) {
        variant =
            product.variants() == null
                ? null
                : product.variants().stream()
                    .filter(v -> line.variantId().equals(v.id()) && !Boolean.FALSE.equals(v.active()))
                    .findFirst()
                    .orElse(null);
        if (variant == null) {
          throw new CodedException(
              ErrorCode.PRODUCT_UNAVAILABLE,
              "That variant is no longer available: " + product.name());
        }
      } else if (hasVariants) {
        throw new CodedException(
            ErrorCode.VALIDATION_FAILED, "Please choose a variant for " + product.name());
      }
      BigDecimal unit = variant != null ? variantPrice(variant, product) : unitPrice(product);
      Offer offer = chooseOffer(product, line);
      UUID offerId = null;
      UUID sellerId = Order.PLATFORM_SELLER;
      if (offer != null) {
        offerId = offer.offerId();
        if (offer.sellerId() != null) {
          sellerId = offer.sellerId();
        }
        if (offer.sellerName() != null) {
          sellerNames.putIfAbsent(sellerId, offer.sellerName());
        }
        if (line.offerId() != null) {
          // The customer picked a specific offer: it sets the price (the buy box is the default).
          BigDecimal offerPrice =
              offer.salePrice() != null && offer.salePrice().signum() > 0
                  ? offer.salePrice()
                  : offer.price();
          if (offerPrice != null) {
            unit = offerPrice;
          }
        }
      }
      if (!product.active() || unit == null) {
        throw new CodedException(
            ErrorCode.PRODUCT_UNAVAILABLE, "Product is no longer available: " + product.name());
      }
      DealPrice deal =
          deals.stream()
              .filter(
                  d ->
                      d.productId().equals(line.productId())
                          && Objects.equals(d.variantId(), line.variantId()))
              .findFirst()
              .orElse(null);
      if (deal != null
          && deal.remaining() >= line.quantity()
          && deal.dealPrice().compareTo(unit) < 0) {
        unit = deal.dealPrice();
        dealClaims.add(new DealClaimItem(deal.dealId(), line.quantity()));
      }
      if (lineDiscountPercent > 0) {
        unit =
            unit.multiply(BigDecimal.valueOf(100L - lineDiscountPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      }
      String lineCurrency = product.priceCurrency();
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
      String image =
          variant != null && variant.imageUrl() != null && !variant.imageUrl().isBlank()
              ? variant.imageUrl()
              : thumbnailOrNull(product.thumbnailUrl());
      order.addItem(
          product.id(),
          variant == null ? null : variant.id(),
          variant == null ? null : variantLabel(variant),
          variant != null && variant.sku() != null ? variant.sku() : product.sku(),
          product.name(),
          unit,
          line.quantity(),
          image,
          offerId,
          sellerId);
    }
    return order;
  }

  /**
   * The offer a line is bought from: the explicitly chosen one (404-style PRODUCT_UNAVAILABLE when
   * it is gone), else the buy box. Null with a product-service that has no offers (platform).
   */
  private static Offer chooseOffer(ProductView product, Line line) {
    List<Offer> offers = product.offers() == null ? List.of() : product.offers();
    if (line.offerId() != null) {
      return offers.stream()
          .filter(o -> line.offerId().equals(o.offerId()))
          .findFirst()
          .orElseThrow(
              () ->
                  new CodedException(
                      ErrorCode.PRODUCT_UNAVAILABLE,
                      "That offer is no longer available: " + product.name()));
    }
    Offer box = product.buyBox();
    if (box != null
        && box.offerId() != null
        && (box.variantId() == null
            || line.variantId() == null
            || box.variantId().equals(line.variantId()))) {
      return box;
    }
    return offers.stream()
        .filter(o -> o.offerId() != null)
        .filter(o -> o.variantId() == null || o.variantId().equals(line.variantId()))
        .min(
            java.util.Comparator.comparing(
                o ->
                    o.salePrice() != null && o.salePrice().signum() > 0
                        ? o.salePrice()
                        : o.price() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : o.price()))
        .orElse(null);
  }

  /** Sale price when set and positive, else the list price. */
  static BigDecimal unitPrice(ProductView p) {
    BigDecimal sale = p.salePrice();
    if (sale != null && sale.signum() > 0) {
      return sale;
    }
    return p.priceAmount();
  }

  /** Variant sale price when positive, else the variant price, else the product rule. */
  static BigDecimal variantPrice(Variant v, ProductView p) {
    if (v.salePrice() != null && v.salePrice().signum() > 0) {
      return v.salePrice();
    }
    return v.priceAmount() != null ? v.priceAmount() : unitPrice(p);
  }

  private static String variantLabel(Variant v) {
    if (v.attributes() == null || v.attributes().isEmpty()) {
      return v.sku();
    }
    String label = String.join(" / ", v.attributes().values());
    return label.length() > 200 ? label.substring(0, 200) : label;
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

  private ProductView fetchProduct(UUID productId) {
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
