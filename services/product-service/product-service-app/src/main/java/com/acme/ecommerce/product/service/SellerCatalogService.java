package com.acme.ecommerce.product.service;

import com.acme.ecommerce.common.core.error.ForbiddenException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.product.api.dto.OfferResponse;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.client.InventoryDto;
import com.acme.ecommerce.product.client.InventoryServiceClient;
import com.acme.ecommerce.product.client.SellerFeignClient.SellerRef;
import com.acme.ecommerce.product.domain.Offer;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.repository.OfferRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.service.OfferService.OfferInput;
import com.acme.ecommerce.product.web.dto.CreateProductRequest;
import com.acme.ecommerce.product.web.dto.ProductImageRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Seller-facing catalog operations: own offers, new products, bulk CSV upload. */
@Service
public class SellerCatalogService {

  public static final int MAX_ROWS = 1000;
  public static final String TEMPLATE_HEADER =
      "sku,name,description,categoryId,brand,price,salePrice,currency,quantity,warehouseCode,imageUrl,attributes";

  public record SellerCtx(UUID sellerId, boolean admin, String sellerName) {}

  public record OfferView(
      OfferResponse offer, UUID productId, String productName, String productSku) {}

  public record OfferRequest(
      UUID productId,
      UUID variantId,
      BigDecimal price,
      BigDecimal salePrice,
      String currency,
      Integer dispatchDays,
      Boolean active,
      Integer quantity,
      String warehouseCode) {}

  public record SellerProductRequest(
      String name,
      String description,
      UUID categoryId,
      String brand,
      String sku,
      String imageUrl,
      BigDecimal price,
      BigDecimal salePrice,
      String currency,
      Integer dispatchDays,
      Integer quantity,
      String warehouseCode) {}

  public record RowError(int row, String message) {}

  public record BulkResult(int created, int updated, List<RowError> errors) {}

  private final SellerDirectory sellers;
  private final OfferService offerService;
  private final OfferRepository offers;
  private final ProductRepository products;
  private final CategoryRepository categories;
  private final ProductCatalogService catalog;
  private final InventoryServiceClient inventory;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;

  @Value("${INTERNAL_API_TOKEN:}")
  private String token;

  public SellerCatalogService(
      SellerDirectory sellers,
      OfferService offerService,
      OfferRepository offers,
      ProductRepository products,
      CategoryRepository categories,
      ProductCatalogService catalog,
      InventoryServiceClient inventory,
      ObjectMapper mapper,
      TransactionTemplate tx) {
    this.sellers = sellers;
    this.offerService = offerService;
    this.offers = offers;
    this.products = products;
    this.categories = categories;
    this.catalog = catalog;
    this.inventory = inventory;
    this.mapper = mapper;
    this.tx = tx;
  }

  /** Resolves the acting seller; admins without a seller record act as the platform. */
  public SellerCtx resolve(String userId, boolean admin) {
    Optional<SellerRef> ref;
    try {
      ref = sellers.byUser(userId);
    } catch (RuntimeException e) {
      if (admin) {
        return new SellerCtx(Offer.PLATFORM_SELLER_ID, true, "Acme Retail");
      }
      throw e;
    }
    if (ref.isPresent() && ref.get().sellerId() != null) {
      if (!"APPROVED".equalsIgnoreCase(ref.get().status()) && !admin) {
        throw new ForbiddenException("Seller account is not approved");
      }
      return new SellerCtx(ref.get().sellerId(), admin, null);
    }
    if (admin) {
      return new SellerCtx(Offer.PLATFORM_SELLER_ID, true, "Acme Retail");
    }
    throw new ForbiddenException("Not a seller");
  }

  public List<OfferView> myOffers(SellerCtx ctx) {
    List<Offer> mine = offerService.bySeller(ctx.sellerId());
    Map<UUID, Product> byProduct = new HashMap<>();
    products.findAllById(mine.stream().map(Offer::getProductId).distinct().toList())
        .forEach(p -> byProduct.put(p.getId(), p));
    Map<UUID, List<Offer>> grouped = new HashMap<>();
    mine.forEach(o -> grouped.computeIfAbsent(o.getProductId(), k -> new ArrayList<>()).add(o));
    Map<UUID, OfferResponse> views = new HashMap<>();
    grouped.forEach(
        (pid, list) -> offerService.views(pid, list).forEach(v -> views.put(v.getOfferId(), v)));
    List<OfferView> out = new ArrayList<>();
    for (Offer o : mine) {
      Product p = byProduct.get(o.getProductId());
      out.add(
          new OfferView(
              views.get(o.getId()),
              o.getProductId(),
              p == null ? null : p.getName(),
              p == null ? null : p.getSku()));
    }
    return out;
  }

  public OfferView createOffer(SellerCtx ctx, OfferRequest r) {
    if (r.productId() == null) {
      throw ValidationException.forField("productId", "is required");
    }
    Offer o =
        offerService.create(
            ctx.sellerId(),
            new OfferInput(
                r.productId(), r.variantId(), r.price(), r.salePrice(), r.currency(), r.dispatchDays(), r.active()));
    writeStock(o, r.quantity(), r.warehouseCode());
    return viewOf(o);
  }

  public OfferView updateOffer(SellerCtx ctx, UUID id, OfferRequest r) {
    Offer o =
        offerService.update(
            ctx.sellerId(),
            id,
            new OfferInput(
                o2(r.productId()), null, r.price(), r.salePrice(), r.currency(), r.dispatchDays(), r.active()),
            ctx.admin());
    writeStock(o, r.quantity(), r.warehouseCode());
    return viewOf(o);
  }

  private static UUID o2(UUID v) {
    return v;
  }

  private OfferView viewOf(Offer o) {
    Product p = products.findById(o.getProductId()).orElse(null);
    return new OfferView(
        offerService.views(o.getProductId(), List.of(o)).get(0),
        o.getProductId(),
        p == null ? null : p.getName(),
        p == null ? null : p.getSku());
  }

  private void writeStock(Offer o, Integer quantity, String warehouseCode) {
    if (quantity == null) {
      return;
    }
    if (quantity < 0) {
      throw ValidationException.forField("quantity", "must be >= 0");
    }
    try {
      inventory.putStock(
          new InventoryDto.StockWrite(
              o.getProductId(), o.getVariantId(), o.getSellerId(), o.getId(), blankToNull(warehouseCode), quantity),
          token);
    } catch (feign.FeignException.BadRequest | feign.FeignException.Forbidden e) {
      throw ValidationException.forField("warehouseCode", "unknown warehouse or seller has none");
    }
    offerService.syncPrices(o.getProductId());
  }

  // ------------------------------------------------------------- products

  public ProductResponse createProduct(SellerCtx ctx, SellerProductRequest r) {
    require(r.name(), "name");
    require(r.sku(), "sku");
    if (r.price() == null) {
      throw ValidationException.forField("price", "is required");
    }
    if (products.findBySku(r.sku().trim()).isPresent()) {
      throw new com.acme.ecommerce.common.core.error.ResourceConflictException(
          "SKU already exists: " + r.sku());
    }
    String currency = r.currency() == null || r.currency().isBlank() ? "USD" : r.currency();
    boolean platform = Offer.PLATFORM_SELLER_ID.equals(ctx.sellerId());
    ProductResponse created =
        tx.execute(
            s -> {
              CreateProductRequest req =
                  CreateProductRequest.builder()
                      .sku(r.sku().trim())
                      .name(r.name().trim())
                      .priceAmount(r.price())
                      .priceCurrency(currency)
                      .active(true)
                      .categoryId(r.categoryId())
                      .brand(r.brand())
                      .sellerName(ctx.sellerName())
                      .images(
                          r.imageUrl() == null || r.imageUrl().isBlank()
                              ? List.of()
                              : List.of(new ProductImageRequest(r.imageUrl().trim(), 0)))
                      .build();
              ProductResponse p =
                  catalog.createFor(req, ctx.sellerId(), platform ? "PLATFORM" : "SELLER");
              Product entity = products.findById(p.getId()).orElseThrow();
              entity.setDescription(blankToNull(r.description()));
              if (r.imageUrl() != null && !r.imageUrl().isBlank()) {
                entity.setThumbnailUrl(r.imageUrl().trim());
              }
              OfferInput in =
                  new OfferInput(p.getId(), null, r.price(), r.salePrice(), currency, r.dispatchDays(), true);
              if (platform) {
                offerService.update(
                    ctx.sellerId(),
                    offers
                        .findByProductIdAndVariantIdIsNullAndSellerId(p.getId(), ctx.sellerId())
                        .orElseThrow()
                        .getId(),
                    in,
                    true);
              } else {
                offerService.create(ctx.sellerId(), in);
              }
              return p;
            });
    Offer offer =
        offers
            .findByProductIdAndVariantIdIsNullAndSellerId(created.getId(), ctx.sellerId())
            .orElseThrow();
    writeStock(offer, r.quantity() == null ? 0 : r.quantity(), r.warehouseCode());
    return catalog.findById(created.getId());
  }

  // ----------------------------------------------------------------- bulk

  public String template() {
    return TEMPLATE_HEADER
        + "\nSKU-001,Sample product,A short description,,Acme,499.00,449.00,INR,25,WH-BLR,https://example.com/a.jpg,\"{\"\"Color\"\":\"\"Red\"\"}\"\n";
  }

  public BulkResult bulk(SellerCtx ctx, byte[] csv) {
    List<List<String>> rows;
    try {
      rows = parseCsv(new String(csv, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw ValidationException.forField("file", e.getMessage());
    }
    if (rows.isEmpty()) {
      throw ValidationException.forField("file", "is empty");
    }
    List<String> header = rows.get(0).stream().map(h -> h.trim().toLowerCase()).toList();
    for (String required : List.of("sku", "name", "price")) {
      if (!header.contains(required)) {
        throw ValidationException.forField("file", "missing column: " + required);
      }
    }
    if (rows.size() - 1 > MAX_ROWS) {
      throw ValidationException.forField("file", "more than " + MAX_ROWS + " rows");
    }
    int created = 0;
    int updated = 0;
    List<RowError> errors = new ArrayList<>();
    for (int i = 1; i < rows.size(); i++) {
      List<String> cells = rows.get(i);
      if (cells.stream().allMatch(String::isBlank)) {
        continue;
      }
      Map<String, String> row = new HashMap<>();
      for (int c = 0; c < header.size(); c++) {
        row.put(header.get(c), c < cells.size() ? cells.get(c).trim() : "");
      }
      final int rowNo = i + 1;
      try {
        Boolean wasCreated = processRow(ctx, row);
        if (wasCreated) {
          created++;
        } else {
          updated++;
        }
      } catch (ValidationException e) {
        errors.add(new RowError(rowNo, describe(e)));
      } catch (RuntimeException e) {
        errors.add(new RowError(rowNo, e.getMessage() == null ? e.toString() : e.getMessage()));
      }
    }
    return new BulkResult(created, updated, errors);
  }

  private static String describe(ValidationException e) {
    return e.fieldErrors().stream()
        .map(m -> m.get("field") + " " + (m.get("message") != null ? m.get("message") : m.get("reason")))
        .collect(java.util.stream.Collectors.joining("; "));
  }

  /** Returns true when a product was created, false when an existing one was updated. */
  private Boolean processRow(SellerCtx ctx, Map<String, String> row) {
    String sku = require(row.get("sku"), "sku");
    String name = require(row.get("name"), "name");
    BigDecimal price = decimal(row.get("price"), "price", true);
    BigDecimal sale = decimal(row.get("salesprice"), "salePrice", false);
    if (sale == null) {
      sale = decimal(row.get("saleprice"), "salePrice", false);
    }
    if (sale != null && sale.compareTo(price) > 0) {
      throw ValidationException.forField("salePrice", "must not exceed price");
    }
    String currency = row.getOrDefault("currency", "");
    currency = currency.isBlank() ? "USD" : currency.toUpperCase();
    if (!currency.matches("^[A-Z]{3}$")) {
      throw ValidationException.forField("currency", "must be a 3-letter code");
    }
    int quantity = 0;
    String q = row.getOrDefault("quantity", "");
    if (!q.isBlank()) {
      try {
        quantity = Integer.parseInt(q);
      } catch (NumberFormatException e) {
        throw ValidationException.forField("quantity", "must be an integer");
      }
      if (quantity < 0) {
        throw ValidationException.forField("quantity", "must be >= 0");
      }
    }
    UUID categoryId = null;
    String cat = row.getOrDefault("categoryid", "");
    if (!cat.isBlank()) {
      try {
        categoryId = UUID.fromString(cat);
      } catch (IllegalArgumentException e) {
        throw ValidationException.forField("categoryId", "must be a UUID");
      }
      if (!categories.existsById(categoryId)) {
        throw ValidationException.forField("categoryId", "unknown category");
      }
    }
    Map<String, Object> attributes = null;
    String attr = row.getOrDefault("attributes", "");
    if (!attr.isBlank()) {
      try {
        attributes = mapper.readValue(attr, new TypeReference<Map<String, Object>>() {});
      } catch (IOException e) {
        throw ValidationException.forField("attributes", "must be a JSON object");
      }
    }
    String warehouse = blankToNull(row.get("warehousecode"));
    String image = blankToNull(row.get("imageurl"));
    String description = blankToNull(row.get("description"));
    String brand = blankToNull(row.get("brand"));

    Product existing = products.findBySku(sku).orElse(null);
    if (existing != null && !existing.getSellerId().equals(ctx.sellerId())) {
      throw ValidationException.forField("sku", "belongs to another seller");
    }
    final UUID categoryFinal = categoryId;
    final Map<String, Object> attrFinal = attributes;
    final BigDecimal saleFinal = sale;
    final String currencyFinal = currency;
    boolean wasCreated = existing == null;
    UUID productId;
    if (wasCreated) {
      boolean platform = Offer.PLATFORM_SELLER_ID.equals(ctx.sellerId());
      productId =
          tx.execute(
              s -> {
                ProductResponse p =
                    catalog.createFor(
                        CreateProductRequest.builder()
                            .sku(sku)
                            .name(name)
                            .priceAmount(price)
                            .priceCurrency(currencyFinal)
                            .active(true)
                            .categoryId(categoryFinal)
                            .brand(brand)
                            .attributes(attrFinal)
                            .sellerName(ctx.sellerName())
                            .images(
                                image == null
                                    ? List.of()
                                    : List.of(new ProductImageRequest(image, 0)))
                            .build(),
                        ctx.sellerId(),
                        platform ? "PLATFORM" : "SELLER");
                Product e = products.findById(p.getId()).orElseThrow();
                e.setDescription(description);
                if (image != null) {
                  e.setThumbnailUrl(image);
                }
                return p.getId();
              });
    } else {
      productId = existing.getId();
      tx.executeWithoutResult(
          s -> {
            Product e = products.findById(existing.getId()).orElseThrow();
            e.setName(name);
            if (description != null) {
              e.setDescription(description);
            }
            if (brand != null) {
              e.setBrand(brand);
            }
            if (categoryFinal != null) {
              e.setCategoryId(categoryFinal);
            }
            if (image != null) {
              e.setThumbnailUrl(image);
            }
          });
    }
    Offer offer =
        offers.findByProductIdAndVariantIdIsNullAndSellerId(productId, ctx.sellerId()).orElse(null);
    if (offer == null) {
      offer =
          offerService.create(
              ctx.sellerId(), new OfferInput(productId, null, price, saleFinal, currencyFinal, 1, true));
    } else {
      offer =
          offerService.update(
              ctx.sellerId(),
              offer.getId(),
              new OfferInput(productId, null, price, saleFinal, currencyFinal, null, true),
              true);
    }
    if (!q.isBlank() || wasCreated) {
      writeStock(offer, quantity, warehouse);
    }
    return wasCreated;
  }

  // ------------------------------------------------------------------ util

  private static String require(String v, String field) {
    if (v == null || v.isBlank()) {
      throw ValidationException.forField(field, "is required");
    }
    return v.trim();
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  private static BigDecimal decimal(String v, String field, boolean required) {
    if (v == null || v.isBlank()) {
      if (required) {
        throw ValidationException.forField(field, "is required");
      }
      return null;
    }
    try {
      BigDecimal d = new BigDecimal(v.trim());
      if (d.signum() < 0) {
        throw ValidationException.forField(field, "must be >= 0");
      }
      return d;
    } catch (NumberFormatException e) {
      throw ValidationException.forField(field, "must be a number");
    }
  }

  /** Minimal RFC 4180 parser: quoted fields, doubled quotes, embedded commas/newlines. */
  static List<List<String>> parseCsv(String text) {
    List<List<String>> rows = new ArrayList<>();
    List<String> cur = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    boolean quoted = false;
    String s = text.startsWith("﻿") ? text.substring(1) : text;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
            sb.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          sb.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        cur.add(sb.toString());
        sb.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
          i++;
        }
        cur.add(sb.toString());
        sb.setLength(0);
        rows.add(cur);
        cur = new ArrayList<>();
      } else {
        sb.append(c);
      }
    }
    if (quoted) {
      throw new IllegalArgumentException("unterminated quoted field");
    }
    if (sb.length() > 0 || !cur.isEmpty()) {
      cur.add(sb.toString());
      rows.add(cur);
    }
    return rows;
  }
}
