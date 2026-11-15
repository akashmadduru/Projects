package com.acme.ecommerce.product.support;

import com.acme.ecommerce.product.domain.Category;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A small catalog for the semantic-search tests. Every sku starts {@code SS-} and every name uses
 * words the Flyway-seeded products do not, so assertions about ranking cannot be disturbed by them.
 */
public final class SearchFixtures {

  public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** Result of {@link #seed}. */
  public record Seeded(
      UUID headphones,
      UUID earbuds,
      UUID runningShoes,
      UUID skillet,
      UUID retired,
      UUID footwear) {}

  private SearchFixtures() {}

  public static Seeded seed(ProductRepository products, CategoryRepository categories) {
    Category footwear =
        categories.saveAndFlush(
            Category.builder().name("Footwear SS").createdAt(NOW).updatedAt(NOW).build());
    Category running =
        categories.saveAndFlush(
            Category.builder()
                .name("Running SS")
                .parentCategoryId(footwear.getId())
                .depthLevel(1)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());

    Product headphones =
        save(
            products,
            "SS-HEADPHONES",
            "Quietcomfort Bluetooth Headphones",
            "Globex",
            "250.00",
            "4.8",
            "Over-ear bluetooth headphones with active noise cancelling.",
            null,
            true);
    Product earbuds =
        save(
            products,
            "SS-EARBUDS",
            "Pocket Bluetooth Earbuds",
            "Globex",
            "60.00",
            "4.0",
            "Tiny bluetooth earbuds with a charging case.",
            null,
            true);
    Product shoes =
        save(
            products,
            "SS-SHOES",
            "Trailblazer Running Shoes",
            "Acme",
            "80.00",
            "4.5",
            "Lightweight trail running shoes with a grippy sole.",
            running.getId(),
            true);
    Product skillet =
        save(
            products,
            "SS-SKILLET",
            "Castiron Skillet Pan",
            "Initech",
            "40.00",
            "4.2",
            "Pre-seasoned cast iron cookware for the stove or oven.",
            null,
            true);
    Product retired =
        save(
            products,
            "SS-RETIRED",
            "Retired Bluetooth Headphones",
            "Globex",
            "30.00",
            "3.0",
            "Discontinued bluetooth headphones.",
            null,
            false);
    return new Seeded(
        headphones.getId(),
        earbuds.getId(),
        shoes.getId(),
        skillet.getId(),
        retired.getId(),
        footwear.getId());
  }

  private static Product save(
      ProductRepository products,
      String sku,
      String name,
      String brand,
      String price,
      String rating,
      String description,
      UUID categoryId,
      boolean active) {
    return products.saveAndFlush(
        Product.builder()
            .sku(sku)
            .name(name)
            .brand(brand)
            .priceAmount(new BigDecimal(price))
            .priceCurrency("USD")
            .averageRating(new BigDecimal(rating))
            .description(description)
            .categoryId(categoryId)
            .active(active)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build());
  }
}
