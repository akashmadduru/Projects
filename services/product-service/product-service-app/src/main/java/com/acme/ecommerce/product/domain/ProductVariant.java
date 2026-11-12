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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One purchasable variant of a {@link Product}. Plain product_id column, no association. */
@Entity
@Table(name = "product_variants")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ProductVariant {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "sku", nullable = false, length = 80)
  private String sku;

  /** JSON string, converted to a Map in the service (same convention as Product.specifications). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
  private String attributes;

  @Column(name = "price_amount")
  private BigDecimal priceAmount;

  @Column(name = "sale_price")
  private BigDecimal salePrice;

  @Column(name = "image_url", length = 2048)
  private String imageUrl;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
