package com.acme.ecommerce.product.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.product.api.dto.ProductResponse;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Guards on the PUBLISHED contract.
 *
 * <p>Everything asserted here is something a consumer - or the gateway's route table - would break
 * on if it changed, and none of it is protected by the compiler.
 */
class ProductContractTest {

  @Test
  @DisplayName("the base path the gateway routes to is fixed")
  void basePathIsStable() {
    // The gateway's `product` route matches Path=/api/products/** and targets
    // lb://PRODUCT-SERVICE. Changing this constant silently orphans that route.
    assertThat(ProductClient.BASE_PATH).isEqualTo("/api/products");
  }

  @Test
  @DisplayName("findById is mapped to GET /api/products/{id}")
  void findByIdMapping() throws NoSuchMethodException {
    Method method = ProductClient.class.getMethod("findById", UUID.class);
    GetMapping mapping = method.getAnnotation(GetMapping.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/api/products/{id}");
  }

  @Test
  @DisplayName("price is an exact decimal with an explicit currency, never a floating-point number")
  void priceIsExactAndCarriesItsCurrency() {
    // A double price loses 0.1 + 0.2 == 0.3 and every consumer inherits the error.
    ProductResponse response =
        ProductResponse.builder()
            .id(UUID.randomUUID())
            .sku("SKU-1")
            .name("Widget")
            .priceAmount(new BigDecimal("19.99"))
            .priceCurrency("USD")
            .active(true)
            .version(0L)
            .images(List.of())
            .build();

    assertThat(response.getPriceAmount()).isEqualByComparingTo(new BigDecimal("19.99"));
    assertThat(response.getPriceAmount().toPlainString()).isEqualTo("19.99");
    assertThat(response.getPriceCurrency()).isEqualTo("USD");
  }
}
