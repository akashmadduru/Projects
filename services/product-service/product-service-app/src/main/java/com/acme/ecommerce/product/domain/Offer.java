package com.acme.ecommerce.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A seller's offer on a product (variant_id null = product level). */
@Entity
@Table(name = "offers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Offer {

  public static final UUID PLATFORM_SELLER_ID =
      UUID.fromString("00000000-0000-0000-0000-00000000a11e");

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "variant_id", updatable = false)
  private UUID variantId;

  @Column(name = "seller_id", nullable = false, updatable = false)
  private UUID sellerId;

  @Column(name = "price", nullable = false)
  private BigDecimal price;

  @Column(name = "sale_price")
  private BigDecimal salePrice;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "condition", nullable = false, length = 20)
  private String condition;

  @Column(name = "dispatch_days", nullable = false)
  private int dispatchDays;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Price the buyer pays: sale price when set and positive, else the base price. */
  public BigDecimal effectivePrice() {
    return salePrice != null && salePrice.signum() > 0 ? salePrice : price;
  }
}
