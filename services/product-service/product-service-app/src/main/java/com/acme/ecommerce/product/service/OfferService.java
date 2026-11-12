package com.acme.ecommerce.product.service;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.product.api.dto.OfferResponse;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.client.InventoryDto;
import com.acme.ecommerce.product.client.InventoryServiceClient;
import com.acme.ecommerce.product.client.SellerFeignClient.SellerInfo;
import com.acme.ecommerce.product.domain.Offer;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.domain.ProductVariant;
import com.acme.ecommerce.product.repository.OfferRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.repository.ProductVariantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Offers, buy box selection and price sync back to the product/variant rows. */
@Service
public class OfferService {

  private static final Logger log = LoggerFactory.getLogger(OfferService.class);
  private static final BigDecimal DEFAULT_RATING = new BigDecimal("4.0");

  private final OfferRepository offers;
  private final ProductRepository products;
  private final ProductVariantRepository variantRepo;
  private final SellerDirectory sellers;
  private final InventoryServiceClient inventory;
  private final TimeProvider time;
  private final ProductCatalogService catalog;

  @Value("${INTERNAL_API_TOKEN:}")
  private String token;

  public OfferService(
      OfferRepository offers,
      ProductRepository products,
      ProductVariantRepository variantRepo,
      SellerDirectory sellers,
      InventoryServiceClient inventory,
      TimeProvider time,
      @org.springframework.context.annotation.Lazy ProductCatalogService catalog) {
    this.offers = offers;
    this.products = products;
    this.variantRepo = variantRepo;
    this.sellers = sellers;
    this.inventory = inventory;
    this.time = time;
    this.catalog = catalog;
  }

  // ---------------------------------------------------------------- reads

  /** Active offers of a product with seller details and stock, plus the buy boxes. */
  public void enrich(ProductResponse response) {
    List<Offer> active = offers.findByProductIdAndActiveTrue(response.getId());
    List<OfferResponse> views = views(response.getId(), active);
    response.setOffers(views);
    response.setBuyBox(buyBox(views.stream().filter(o -> o.getVariantId() == null).toList()));
    Map<UUID, OfferResponse> perVariant = new LinkedHashMap<>();
    views.stream()
        .map(OfferResponse::getVariantId)
        .filter(Objects::nonNull)
        .distinct()
        .forEach(
            v ->
                perVariant.put(
                    v, buyBox(views.stream().filter(o -> v.equals(o.getVariantId())).toList())));
    response.setVariantBuyBoxes(perVariant);
  }

  public List<OfferResponse> views(UUID productId, List<Offer> list) {
    Map<UUID, SellerInfo> info =
        sellers.info(list.stream().map(Offer::getSellerId).distinct().toList());
    List<InventoryDto.StockRow> rows = stockRows(productId);
    List<OfferResponse> out = new ArrayList<>();
    for (Offer o : list) {
      out.add(view(o, info.get(o.getSellerId()), rows));
    }
    return out;
  }

  private OfferResponse view(Offer o, SellerInfo s, List<InventoryDto.StockRow> rows) {
    return OfferResponse.builder()
        .offerId(o.getId())
        .variantId(o.getVariantId())
        .sellerId(o.getSellerId())
        .sellerName(s == null ? "Seller" : s.displayName())
        .sellerSlug(s == null ? null : s.slug())
        .sellerRating(s == null ? null : s.ratingAvg())
        .price(o.getPrice())
        .salePrice(o.getSalePrice())
        .currency(o.getCurrency())
        .dispatchDays(o.getDispatchDays())
        .active(o.isActive())
        .availableQuantity(quantity(o, rows))
        .build();
  }

  /** Null = unknown / untracked (counts as in stock); platform without rows = untracked. */
  private static Integer quantity(Offer o, List<InventoryDto.StockRow> rows) {
    if (rows == null) {
      return null;
    }
    List<InventoryDto.StockRow> mine =
        rows.stream()
            .filter(
                r ->
                    r.getSellerId().equals(o.getSellerId())
                        && Objects.equals(r.getVariantId(), o.getVariantId()))
            .toList();
    if (mine.isEmpty()) {
      return Offer.PLATFORM_SELLER_ID.equals(o.getSellerId()) ? null : 0;
    }
    return mine.stream().mapToInt(InventoryDto.StockRow::getQuantity).sum();
  }

  private List<InventoryDto.StockRow> stockRows(UUID productId) {
    try {
      return inventory.stockRows(productId, token);
    } catch (RuntimeException e) {
      return null;
    }
  }

  static BigDecimal effective(OfferResponse o) {
    return o.getSalePrice() != null && o.getSalePrice().signum() > 0 ? o.getSalePrice() : o.getPrice();
  }

  /** score = effective * (1 + 0.05 * (5 - rating)) * (1 + 0.005 * dispatchDays); lowest wins, ties -> platform. */
  static OfferResponse buyBox(List<OfferResponse> candidates) {
    List<OfferResponse> active = candidates.stream().filter(OfferResponse::isActive).toList();
    List<OfferResponse> inStock =
        active.stream()
            .filter(o -> o.getAvailableQuantity() == null || o.getAvailableQuantity() > 0)
            .toList();
    List<OfferResponse> pool = inStock.isEmpty() ? active : inStock;
    return pool.stream()
        .min(
            Comparator.comparingDouble(OfferService::score)
                .thenComparing(o -> Offer.PLATFORM_SELLER_ID.equals(o.getSellerId()) ? 0 : 1))
        .orElse(null);
  }

  private static double score(OfferResponse o) {
    BigDecimal rating = o.getSellerRating() == null ? DEFAULT_RATING : o.getSellerRating();
    return effective(o).doubleValue()
        * (1 + 0.05 * (5 - rating.doubleValue()))
        * (1 + 0.005 * o.getDispatchDays());
  }

  public Offer get(UUID id) {
    return offers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Offer", id));
  }

  public List<Offer> bySeller(UUID sellerId) {
    return offers.findBySellerIdOrderByCreatedAtDesc(sellerId);
  }

  // --------------------------------------------------------------- writes

  public record OfferInput(
      UUID productId,
      UUID variantId,
      BigDecimal price,
      BigDecimal salePrice,
      String currency,
      Integer dispatchDays,
      Boolean active) {}

  @Transactional
  public Offer create(UUID sellerId, OfferInput in) {
    Product p =
        products
            .findById(in.productId())
            .orElseThrow(() -> new ResourceNotFoundException("Product", in.productId()));
    if (in.variantId() != null) {
      ProductVariant v =
          variantRepo
              .findById(in.variantId())
              .orElseThrow(() -> new ResourceNotFoundException("Variant", in.variantId()));
      if (!v.getProductId().equals(p.getId())) {
        throw ValidationException.forField("variantId", "does not belong to the product");
      }
    }
    validatePrices(in.price(), in.salePrice());
    var existing =
        in.variantId() == null
            ? offers.findByProductIdAndVariantIdIsNullAndSellerId(p.getId(), sellerId)
            : offers.findByProductIdAndVariantIdAndSellerId(p.getId(), in.variantId(), sellerId);
    if (existing.isPresent()) {
      throw new ResourceConflictException("This seller already has an offer for that product/variant");
    }
    Instant now = time.now();
    Offer o =
        Offer.builder()
            .id(UUID.randomUUID())
            .productId(p.getId())
            .variantId(in.variantId())
            .sellerId(sellerId)
            .price(in.price())
            .salePrice(in.salePrice())
            .currency(in.currency() == null || in.currency().isBlank() ? p.getPriceCurrency() : in.currency())
            .condition("NEW")
            .dispatchDays(in.dispatchDays() == null ? 1 : Math.max(0, in.dispatchDays()))
            .active(in.active() == null || in.active())
            .createdAt(now)
            .updatedAt(now)
            .build();
    offers.saveAndFlush(o);
    syncPrices(p.getId());
    return o;
  }

  @Transactional
  public Offer update(UUID sellerId, UUID offerId, OfferInput in, boolean admin) {
    Offer o = get(offerId);
    if (!admin && !o.getSellerId().equals(sellerId)) {
      throw new com.acme.ecommerce.common.core.error.ForbiddenException("Not your offer");
    }
    BigDecimal price = in.price() != null ? in.price() : o.getPrice();
    BigDecimal sale = in.salePrice() != null ? in.salePrice() : o.getSalePrice();
    validatePrices(price, sale);
    o.setPrice(price);
    o.setSalePrice(sale != null && sale.signum() == 0 ? null : sale);
    if (in.currency() != null && !in.currency().isBlank()) {
      o.setCurrency(in.currency());
    }
    if (in.dispatchDays() != null) {
      o.setDispatchDays(Math.max(0, in.dispatchDays()));
    }
    if (in.active() != null) {
      o.setActive(in.active());
    }
    o.setUpdatedAt(time.now());
    offers.saveAndFlush(o);
    syncPrices(o.getProductId());
    return o;
  }

  private static void validatePrices(BigDecimal price, BigDecimal sale) {
    if (price == null || price.signum() < 0) {
      throw ValidationException.forField("price", "must be >= 0");
    }
    if (sale != null && (sale.signum() < 0 || sale.compareTo(price) > 0)) {
      throw ValidationException.forField("salePrice", "must be between 0 and price");
    }
  }

  /** Platform product-level offer (created with the catalog product / kept in step with edits). */
  @Transactional
  public void upsertPlatformOffer(UUID productId, BigDecimal price, String currency) {
    Instant now = time.now();
    Offer o =
        offers
            .findByProductIdAndVariantIdIsNullAndSellerId(productId, Offer.PLATFORM_SELLER_ID)
            .orElse(null);
    if (o == null) {
      o =
          Offer.builder()
              .id(UUID.randomUUID())
              .productId(productId)
              .sellerId(Offer.PLATFORM_SELLER_ID)
              .condition("NEW")
              .dispatchDays(1)
              .active(true)
              .createdAt(now)
              .build();
    }
    o.setPrice(price);
    o.setSalePrice(null);
    o.setCurrency(currency);
    o.setUpdatedAt(now);
    offers.saveAndFlush(o);
  }

  /** Recomputes the buy boxes and writes the winning prices into the product/variant rows. */
  @Transactional
  public void syncPrices(UUID productId) {
    Product p = products.findById(productId).orElse(null);
    if (p == null) {
      return;
    }
    List<OfferResponse> views = views(productId, offers.findByProductIdAndActiveTrue(productId));
    OfferResponse productBox =
        buyBox(views.stream().filter(o -> o.getVariantId() == null).toList());
    if (productBox != null && Objects.equals(productBox.getCurrency(), p.getPriceCurrency())) {
      catalog.applyBuyBoxPrice(productId, effective(productBox));
    }
    Map<UUID, List<OfferResponse>> byVariant = new HashMap<>();
    views.stream()
        .filter(o -> o.getVariantId() != null)
        .forEach(o -> byVariant.computeIfAbsent(o.getVariantId(), k -> new ArrayList<>()).add(o));
    byVariant.forEach(
        (variantId, list) -> {
          OfferResponse box = buyBox(list);
          if (box == null) {
            return;
          }
          variantRepo
              .findById(variantId)
              .ifPresent(
                  v -> {
                    if (box.getPrice().compareTo(v.getPriceAmount() == null ? BigDecimal.valueOf(-1) : v.getPriceAmount()) != 0
                        || !Objects.equals(box.getSalePrice(), v.getSalePrice())) {
                      v.setPriceAmount(box.getPrice());
                      v.setSalePrice(box.getSalePrice());
                      v.setUpdatedAt(time.now());
                      variantRepo.save(v);
                    }
                  });
        });
    log.debug("synced prices for {}", productId);
  }
}
