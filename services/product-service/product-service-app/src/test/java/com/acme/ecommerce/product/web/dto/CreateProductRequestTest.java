package com.acme.ecommerce.product.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code isInitialStockValidWhenActive()}, the {@code
 * RequiresInitialStockIfActiveRule} reclassified as ordinary Bean Validation
 * (docs/architecture/design/HLD-catalog-enrichment-and-saga.md S7.2,
 * docs/architecture/design/LLD-catalog-enrichment-and-saga.md S2.1) - the choreographed
 * creation-saga's synchronous, pre-transaction validation gate.
 *
 * <p>Exercises the real {@link jakarta.validation.Validator} - the same engine {@code @Valid} on
 * {@code ProductController} invokes at the HTTP boundary - rather than calling {@code
 * isInitialStockValidWhenActive()} directly, so this also proves the {@code @AssertTrue} wiring
 * itself (message, property path) rather than only the boolean logic.
 */
class CreateProductRequestTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDownValidator() {
    factory.close();
  }

  @Test
  @DisplayName("active=true with initialQuantity >= 1 is valid")
  void activeWithSufficientInitialQuantityIsValid() {
    CreateProductRequest request = request(true, 1);

    Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(request);

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("active=true with initialQuantity omitted (null) is rejected")
  void activeWithNullInitialQuantityIsRejected() {
    CreateProductRequest request = request(true, null);

    Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(request);

    assertThat(violations)
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("initialQuantity must be >= 1 when active=true");
  }

  @Test
  @DisplayName("active=true with initialQuantity == 0 is rejected")
  void activeWithZeroInitialQuantityIsRejected() {
    CreateProductRequest request = request(true, 0);

    Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(request);

    assertThat(violations)
        .extracting(ConstraintViolation::getMessage)
        .containsExactly("initialQuantity must be >= 1 when active=true");
  }

  @Test
  @DisplayName("active=false with initialQuantity omitted (null) is valid - not a saga case at all")
  void inactiveWithNullInitialQuantityIsValid() {
    CreateProductRequest request = request(false, null);

    Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(request);

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("active=false with initialQuantity == 0 is valid")
  void inactiveWithZeroInitialQuantityIsValid() {
    CreateProductRequest request = request(false, 0);

    Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(request);

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("a negative initialQuantity is rejected by @Min(0), independent of active")
  void negativeInitialQuantityIsRejectedRegardlessOfActive() {
    CreateProductRequest request = request(false, -1);

    Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(request);

    assertThat(violations)
        .extracting(ConstraintViolation::getPropertyPath)
        .extracting(Object::toString)
        .containsExactly("initialQuantity");
  }

  private static CreateProductRequest request(boolean active, Integer initialQuantity) {
    return new CreateProductRequest(
        "SKU-01",
        "Widget",
        new BigDecimal("9.99"),
        "USD",
        active,
        initialQuantity,
        null,
        null,
        null,
        null);
  }
}
