package com.acme.ecommerce.product.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A catalog product as exposed to callers. Uses Lombok @Data for full POJO functionality with
 * fine-grained control.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {
  private UUID id;
  private String sku;
  private String name;
  private BigDecimal priceAmount;
  private String priceCurrency;
  private boolean active;
  private Long version;
  private UUID categoryId;
  private String sellerName;
  private Map<String, Object> specifications;
  private List<ProductImageResponse> images;
  private Integer lastKnownQuantity;
  private String description;
  private BigDecimal listPrice;
  private BigDecimal salePrice;
  private BigDecimal discountPercent;
  private String thumbnailUrl;
  private List<String> videoUrls;
  private String view360Url;
  private Integer weightGrams;
  private BigDecimal dimensionsLength;
  private BigDecimal dimensionsWidth;
  private BigDecimal dimensionsHeight;
  private String color;
  private String material;
  private BigDecimal averageRating;
  private int ratingCount;
  private int reviewCount;
  private Map<Integer, Integer> ratingDistribution;
  private boolean isFeatured;
  private BigDecimal sellerRating;
  private boolean isSellerVerified;
}
