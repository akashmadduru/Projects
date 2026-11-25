package com.acme.ecommerce.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.product.repository.ProductFilterNativeSql.Omit;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductFilterNativeSqlTest {

  private static final String NONE_SQL =
      ProductFilterNativeSql.of(ProductFilter.none(), null, Omit.NONE).sql();

  @Test
  @DisplayName(
      "every ProductFilter component narrows the SQL (fails when one is added untranslated)")
  void everyComponentIsTranslated() throws Exception {
    RecordComponent[] components = ProductFilter.class.getRecordComponents();
    for (int i = 0; i < components.length; i++) {
      Object[] args = new Object[components.length];
      for (int j = 0; j < components.length; j++) {
        args[j] = j == i ? sample(components[j]) : empty(components[j]);
      }
      ProductFilter filter =
          ProductFilter.class
              .getDeclaredConstructor(
                  Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new))
              .newInstance(args);
      assertThat(ProductFilterNativeSql.of(filter, null, Omit.NONE).sql())
          .as(components[i].getName() + " has no SQL clause in ProductFilterNativeSql")
          .isNotEqualTo(NONE_SQL);
    }
  }

  @Test
  @DisplayName("each facet drops only its own filter")
  void omitsOwnFilter() {
    ProductFilter filter =
        new ProductFilter(
                null,
                null,
                BigDecimal.ONE,
                BigDecimal.TEN,
                null,
                null,
                null,
                false,
                false,
                false,
                false)
            .withBrandsAndAttributes(
                List.of("Acme"), Map.of("Color", List.of("Red"), "Size", List.of("M")));

    String brandsOmitted = ProductFilterNativeSql.of(filter, null, Omit.BRANDS).sql();
    assertThat(brandsOmitted).doesNotContain("p.brand").contains("price_amount", "->>");

    String priceOmitted = ProductFilterNativeSql.of(filter, null, Omit.PRICE).sql();
    assertThat(priceOmitted).doesNotContain("price_amount").contains("p.brand");

    var colorOmitted = ProductFilterNativeSql.of(filter, null, Omit.attribute("Color"));
    assertThat(colorOmitted.params()).containsValue("Size").doesNotContainValue("Color");
    assertThat(colorOmitted.params().get("brands")).isEqualTo(List.of("acme"));
  }

  @Test
  @DisplayName("a query with no search hits matches nothing; values are bound, never concatenated")
  void boundValues() {
    ProductFilter filter =
        new ProductFilter(
            null, "x' or 1=1 --", null, null, null, null, null, false, false, false, false);
    assertThat(ProductFilterNativeSql.of(filter, List.of(), Omit.NONE).sql()).endsWith("and false");
    var substring = ProductFilterNativeSql.of(filter, null, Omit.NONE);
    assertThat(substring.sql()).doesNotContain("1=1");
    assertThat(substring.params().get("queryPattern")).isEqualTo("%x' or 1=1 --%");
    assertThat(ProductFilterNativeSql.ofIds(List.of()).sql()).isEqualTo("false");
  }

  private static Object sample(RecordComponent c) {
    Class<?> type = c.getType();
    if (type == boolean.class) {
      return true;
    }
    if (type == UUID.class) {
      return UUID.randomUUID();
    }
    if (type == String.class) {
      return "abc";
    }
    if (type == BigDecimal.class) {
      return BigDecimal.ONE;
    }
    if (type == List.class) {
      return List.of("Acme");
    }
    if (type == Map.class) {
      return Map.of("Color", List.of("Red"));
    }
    throw new AssertionError("Unhandled component type " + type + " for " + c.getName());
  }

  private static Object empty(RecordComponent c) {
    Class<?> type = c.getType();
    if (type == boolean.class) {
      return false;
    }
    if (type == List.class) {
      return List.of();
    }
    if (type == Map.class) {
      return Map.of();
    }
    return null;
  }
}
