package com.acme.ecommerce.order.service;

import com.acme.ecommerce.cart.api.dto.CartItemResponse;
import com.acme.ecommerce.cart.api.dto.CartResponse;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.DomainRuleViolationException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.client.CartFeignClient;
import com.acme.ecommerce.order.client.ProductFeignClient;
import com.acme.ecommerce.order.client.UserFeignClient;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.user.api.dto.AddressResponse;
import feign.FeignException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Checkout orchestration: cart, address and product lookups are remote calls made on the REQUEST
 * thread (so the relayed bearer token is available), sequentially, with no database transaction
 * open. Only the final insert is transactional, in {@link OrderStore}.
 *
 * <p>Prices are re-read from product-service (sale price when positive, else list price); the
 * display prices stored in the cart are never trusted.
 */
@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);
  private static final int MAX_LINES = 50;

  /**
   * Outcome of a placement: the order and whether it was newly created (vs an idempotent replay).
   */
  public record Placement(OrderResponse order, boolean created) {}

  private final OrderStore store;
  private final CartFeignClient carts;
  private final UserFeignClient users;
  private final ProductFeignClient products;

  public OrderService(
      OrderStore store, CartFeignClient carts, UserFeignClient users, ProductFeignClient products) {
    this.store = store;
    this.carts = carts;
    this.users = users;
    this.products = products;
  }

  public Placement place(UUID userId, UUID addressId, String idempotencyKey) {
    Optional<OrderResponse> replay = store.findReplay(userId, idempotencyKey, addressId);
    if (replay.isPresent()) {
      return new Placement(replay.get(), false);
    }

    CartResponse cart = call("cart-service", carts::getCart);
    if (cart == null || cart.items() == null || cart.items().isEmpty()) {
      throw new CodedException(ErrorCode.CART_EMPTY, "Your cart is empty");
    }
    if (cart.items().size() > MAX_LINES) {
      throw new DomainRuleViolationException(
          "An order can have at most " + MAX_LINES + " distinct products");
    }

    AddressResponse address = fetchAddress(addressId);
    Order order = build(userId, idempotencyKey, address, cart.items());

    OrderResponse saved;
    try {
      saved = store.save(order);
    } catch (DataIntegrityViolationException race) {
      // A concurrent request with the same key won the unique constraint: return its order.
      Optional<OrderResponse> winner = store.findReplay(userId, idempotencyKey, addressId);
      if (winner.isEmpty()) {
        throw race;
      }
      return new Placement(winner.get(), false);
    }

    clearCartBestEffort(saved.id());
    return new Placement(saved, true);
  }

  private Order build(
      UUID userId, String key, AddressResponse address, List<CartItemResponse> lines) {
    Order order = null;
    String currency = null;
    for (CartItemResponse line : lines) {
      ProductResponse product = fetchProduct(line.productId());
      if (!product.isActive() || unitPrice(product) == null) {
        throw new CodedException(
            ErrorCode.PRODUCT_UNAVAILABLE, "Product is no longer available: " + line.name());
      }
      String lineCurrency = product.getPriceCurrency();
      if (currency == null) {
        currency = lineCurrency;
        order =
            Order.place(
                userId,
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
                Instant.now());
      } else if (!currency.equals(lineCurrency)) {
        throw new CodedException(
            ErrorCode.CURRENCY_MISMATCH, "Products in the cart use different currencies");
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
      throw dependencyUnavailable("user-service", e);
    }
  }

  private ProductResponse fetchProduct(UUID productId) {
    try {
      return products.findById(productId);
    } catch (FeignException.NotFound e) {
      throw new CodedException(
          ErrorCode.PRODUCT_UNAVAILABLE, "A product in your cart no longer exists");
    } catch (FeignException e) {
      throw dependencyUnavailable("product-service", e);
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
      throw dependencyUnavailable(service, e);
    }
  }

  private static CodedException dependencyUnavailable(String service, FeignException e) {
    log.warn("Dependency {} failed: status={} {}", service, e.status(), e.getMessage());
    return new CodedException(ErrorCode.DEPENDENCY_UNAVAILABLE, service + " is unavailable", e);
  }

  /** Only the catalog thumbnail is trusted; the cart's client-supplied imageUrl is never copied. */
  private static String thumbnailOrNull(String thumbnail) {
    return thumbnail != null && !thumbnail.isBlank() ? thumbnail : null;
  }
}
