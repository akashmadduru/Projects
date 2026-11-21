package com.acme.ecommerce.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.product.domain.Product;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TD-P7-05: the admin can set or clear the list price; the paid price follows every edit. */
class ProductPricingTest {

  private static Product product(String price, String listPrice, String salePrice) {
    Product p = Product.builder().sku("PP-1").name("Priced").priceCurrency("USD").build();
    p.setPriceAmount(new BigDecimal(price));
    p.setListPrice(listPrice == null ? null : new BigDecimal(listPrice));
    p.setSalePrice(salePrice == null ? null : new BigDecimal(salePrice));
    return p;
  }

  @Test
  @DisplayName("a list price makes sale_price mirror the selling price and sets the discount")
  void listPriceSetsDiscount() {
    Product p = product("100.00", null, null);
    ProductCatalogService.applyListPrice(p, new BigDecimal("125.00"), new BigDecimal("100.00"));
    ProductCatalogService.applySellingPrice(p, new BigDecimal("100.00"));

    assertThat(p.getListPrice()).isEqualByComparingTo("125.00");
    assertThat(p.getSalePrice()).isEqualByComparingTo("100.00");
    assertThat(p.getDiscountPercent()).isEqualByComparingTo("20.00");
  }

  @Test
  @DisplayName("a price edit moves sale_price and the discount with it")
  void priceEditFollows() {
    Product p = product("80.00", "100.00", "80.00");
    ProductCatalogService.applySellingPrice(p, new BigDecimal("90.00"));

    assertThat(p.getSalePrice()).isEqualByComparingTo("90.00");
    assertThat(p.getDiscountPercent()).isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("0 clears the list price and the discount; a list price below the price is rejected")
  void clearAndReject() {
    Product p = product("80.00", "100.00", "80.00");
    p.setDiscountPercent(new BigDecimal("20.00"));
    ProductCatalogService.applyListPrice(p, BigDecimal.ZERO, new BigDecimal("80.00"));
    assertThat(p.getListPrice()).isNull();
    assertThat(p.getDiscountPercent()).isNull();

    assertThatThrownBy(
            () ->
                ProductCatalogService.applyListPrice(
                    p, new BigDecimal("70.00"), new BigDecimal("80.00")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  @DisplayName("without a sale or list price nothing but the price changes")
  void plainPrice() {
    Product p = product("50.00", null, null);
    ProductCatalogService.applySellingPrice(p, new BigDecimal("55.00"));
    assertThat(p.getPriceAmount()).isEqualByComparingTo("55.00");
    assertThat(p.getSalePrice()).isNull();
    assertThat(p.getDiscountPercent()).isNull();
  }
}
