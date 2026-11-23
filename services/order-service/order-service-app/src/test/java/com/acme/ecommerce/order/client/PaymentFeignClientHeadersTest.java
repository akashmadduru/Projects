package com.acme.ecommerce.order.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.web.feign.CorrelationAndAuthForwardingInterceptor;
import com.acme.ecommerce.order.client.PaymentFeignClient.CreatePayment;
import com.acme.ecommerce.order.config.OrderProperties;
import feign.Capability;
import feign.Feign;
import feign.Request;
import feign.RequestInterceptor;
import feign.Response;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * B-10 security fix: the COD ensure now runs on admin refund and collect request threads, so the
 * payment client must send X-Internal-Token and never the caller's bearer.
 */
class PaymentFeignClientHeadersTest {

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  private Request capture(boolean withStripper, Consumer<PaymentFeignClient> call) {
    OrderProperties props = new OrderProperties();
    props.getInternal().setToken("secret-token");
    AtomicReference<Request> seen = new AtomicReference<>();
    Feign.Builder b =
        Feign.builder()
            .contract(new SpringMvcContract())
            .encoder((o, t, template) -> template.body("{}"))
            .decoder((r, t) -> null)
            .client(
                (req, opts) -> {
                  seen.set(req);
                  return Response.builder().status(200).request(req).headers(Map.of()).build();
                })
            .requestInterceptor(new CorrelationAndAuthForwardingInterceptor())
            .requestInterceptor(
                (RequestInterceptor) new InternalFeignConfig().internalTokenInterceptor(props));
    if (withStripper) {
      b.addCapability((Capability) new NoUserTokenFeignConfig().stripUserTokenCapability());
    }
    MockHttpServletRequest inbound = new MockHttpServletRequest();
    inbound.addHeader("Authorization", "Bearer admin-jwt");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
    call.accept(b.target(PaymentFeignClient.class, "http://payment-service"));
    return seen.get();
  }

  private static CreatePayment body() {
    return new CreatePayment(
        UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "INR", "COD");
  }

  @Test
  @DisplayName("ensureRow and create send X-Internal-Token and no Authorization header")
  void internalTokenNoBearer() {
    for (Consumer<PaymentFeignClient> call :
        java.util.List.<Consumer<PaymentFeignClient>>of(
            c -> c.ensureRow(body()), c -> c.create(body()))) {
      Request sent = capture(true, call);
      assertThat(sent.headers().get("X-Internal-Token")).containsExactly("secret-token");
      assertThat(sent.headers()).doesNotContainKey("Authorization");
    }
  }

  @Test
  @DisplayName("positive sanity: without the stripper the bearer would be forwarded")
  void withoutStripperBearerLeaks() {
    Request sent = capture(false, c -> c.ensureRow(body()));
    assertThat(sent.headers().get("Authorization")).containsExactly("Bearer admin-jwt");
  }

  @Test
  @DisplayName("the client is wired to the token config and the stripper")
  void clientReferencesBothConfigs() {
    assertThat(PaymentFeignClient.class.getAnnotation(FeignClient.class).configuration())
        .contains(InternalFeignConfig.class, NoUserTokenFeignConfig.class);
  }
}
