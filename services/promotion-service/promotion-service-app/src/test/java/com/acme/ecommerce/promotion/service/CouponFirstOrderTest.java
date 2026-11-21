package com.acme.ecommerce.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.promotion.api.dto.AvailableCouponResponse;
import com.acme.ecommerce.promotion.api.dto.ValidateCouponRequest;
import com.acme.ecommerce.promotion.client.OrderFeignClient;
import com.acme.ecommerce.promotion.client.OrderFeignClient.FirstOrderStatus;
import com.acme.ecommerce.promotion.repository.CouponRepository;
import com.acme.ecommerce.promotion.repository.CouponRepository.Coupon;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TD-P6-16: first-order coupons are hidden from and rejected for returning customers. */
class CouponFirstOrderTest {

  private final CouponRepository repo = mock(CouponRepository.class);
  private final OrderFeignClient orders = mock(OrderFeignClient.class);
  private final CouponService service = new CouponService(repo, orders);
  private final UUID user = UUID.randomUUID();

  private static Coupon coupon(String code, boolean firstOrderOnly) {
    return new Coupon(
        UUID.randomUUID(),
        code,
        code,
        "PERCENT",
        new BigDecimal("10"),
        null,
        BigDecimal.ZERO,
        null,
        null,
        null,
        null,
        null,
        firstOrderOnly,
        true,
        0,
        Instant.now(),
        Instant.now());
  }

  @Test
  void returningCustomerDoesNotSeeFirstOrderCoupons() {
    when(repo.findActive()).thenReturn(List.of(coupon("WELCOME10", true), coupon("SAVE5", false)));
    when(orders.firstOrder(user)).thenReturn(new FirstOrderStatus(false));

    List<AvailableCouponResponse> list = service.available(user, null, null);

    assertThat(list).extracting(AvailableCouponResponse::code).containsExactly("SAVE5");
  }

  @Test
  void newCustomerSeesFirstOrderCoupons() {
    when(repo.findActive()).thenReturn(List.of(coupon("WELCOME10", true), coupon("SAVE5", false)));
    when(orders.firstOrder(user)).thenReturn(new FirstOrderStatus(true));

    assertThat(service.available(user, null, null))
        .extracting(AvailableCouponResponse::code)
        .containsExactly("WELCOME10", "SAVE5");
  }

  @Test
  void orderServiceDownKeepsTheCouponListed() {
    when(repo.findActive()).thenReturn(List.of(coupon("WELCOME10", true)));
    when(orders.firstOrder(any())).thenThrow(new RuntimeException("down"));

    assertThat(service.available(user, null, null)).hasSize(1);
  }

  @Test
  void noLookupWhenNoFirstOrderCouponExists() {
    when(repo.findActive()).thenReturn(List.of(coupon("SAVE5", false)));

    service.available(user, null, null);

    verify(orders, never()).firstOrder(any());
  }

  @Test
  void validateRejectsFirstOrderCouponForReturningCustomer() {
    when(repo.findByCode("WELCOME10")).thenReturn(Optional.of(coupon("WELCOME10", true)));
    when(orders.firstOrder(user)).thenReturn(new FirstOrderStatus(false));

    assertThatThrownBy(
            () ->
                service.validate(
                    user,
                    new ValidateCouponRequest("welcome10", new BigDecimal("500"), "INR", null)))
        .isInstanceOf(CodedException.class)
        .hasMessageContaining("first order");
  }

  @Test
  void validateTrustsAnExplicitFirstOrderFlag() {
    when(repo.findByCode("WELCOME10")).thenReturn(Optional.of(coupon("WELCOME10", true)));

    assertThat(
            service
                .validate(
                    user,
                    new ValidateCouponRequest("WELCOME10", new BigDecimal("500"), "INR", true))
                .discountAmount())
        .isEqualByComparingTo("50");
    verify(orders, never()).firstOrder(any());
  }
}
