package com.acme.ecommerce.product.api.dto;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One seller's offer on a product (optionally a specific variant). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfferResponse {
  private UUID offerId;
  private UUID variantId;
  private UUID sellerId;
  private String sellerName;
  private String sellerSlug;
  private BigDecimal sellerRating;
  private BigDecimal price;
  private BigDecimal salePrice;
  private String currency;
  private int dispatchDays;
  private boolean active;

  /** Null when inventory is unknown or does not track the offer (treated as in stock). */
  private Integer availableQuantity;
}
