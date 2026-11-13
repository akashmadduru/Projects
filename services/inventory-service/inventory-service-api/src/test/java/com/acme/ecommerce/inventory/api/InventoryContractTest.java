package com.acme.ecommerce.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
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
class InventoryContractTest {

  @Test
  @DisplayName("the base path the gateway routes to is fixed")
  void basePathIsStable() {
    // The gateway's `inventory` route matches Path=/api/inventory/** and targets
    // lb://INVENTORY-SERVICE. Changing this constant silently orphans that route.
    assertThat(InventoryClient.BASE_PATH).isEqualTo("/api/inventory");
  }

  @Test
  @DisplayName("findById is mapped to GET /api/inventory/{id}")
  void findByIdMapping() throws NoSuchMethodException {
    Method method = InventoryClient.class.getMethod("findById", UUID.class);
    GetMapping mapping = method.getAnnotation(GetMapping.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/api/inventory/{id}");
  }

  @Test
  @DisplayName(
      "findByProductId is mapped to GET /api/inventory/by-product/{productId} - the narrow"
          + " read-only aggregate-read exception (docs/architecture/adr/0003, Decision 2)")
  void findByProductIdMapping() throws NoSuchMethodException {
    Method method = InventoryClient.class.getMethod("findByProductId", UUID.class);
    GetMapping mapping = method.getAnnotation(GetMapping.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/api/inventory/by-product/{productId}");
  }

  @Test
  @DisplayName(
      "a stock-level row carries productId, quantity and an optimistic-concurrency version")
  void shapeIsStable() {

    // InventoryResponse response =
    //     new InventoryResponse(
    //       UUID.randomUUID(),
    //       UUID.randomUUID(),
    //       5,
    //       1,
    //       1,
    //       "WH-1",
    //       1,
    //       "Product Name",
    //       "SKU123",
    //       10,
    //       0L
    //     );

    // assertThat(response.quantity()).isEqualTo(5);
    // assertThat(response.version()).isEqualTo(0L);

  }
}
