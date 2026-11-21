package com.acme.ecommerce.order.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.cart.api.dto.ConsumedLinesRequest;
import com.acme.ecommerce.cart.api.dto.ConsumedLinesResponse;
import com.acme.ecommerce.common.web.feign.CorrelationAndAuthForwardingInterceptor;
import com.acme.ecommerce.order.config.OrderProperties;
import feign.Capability;
import feign.Feign;
import feign.Request;
import feign.RequestInterceptor;
import feign.Response;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CartInternalFeignClientHeadersTest {

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  private Request captureCall(boolean withStripper) {
    OrderProperties props = new OrderProperties();
    props.getInternal().setToken("secret-token");
    AtomicReference<Request> seen = new AtomicReference<>();
    Feign.Builder b =
        Feign.builder()
            .contract(new SpringMvcContract())
            .encoder((o, t, template) -> template.body("{}"))
            .decoder((r, t) -> new ConsumedLinesResponse(0))
            .client(
                (req, opts) -> {
                  seen.set(req);
                  return Response.builder().status(200).request(req).headers(Map.of()).build();
                })
            // Same order Spring builds: global forwarding interceptor, then per-client token.
            .requestInterceptor(new CorrelationAndAuthForwardingInterceptor())
            .requestInterceptor(
                (RequestInterceptor) new InternalFeignConfig().internalTokenInterceptor(props));
    if (withStripper) {
      b.addCapability((Capability) new NoUserTokenFeignConfig().stripUserTokenCapability());
    }
    CartInternalFeignClient client = b.target(CartInternalFeignClient.class, "http://cart-service");

    MockHttpServletRequest inbound = new MockHttpServletRequest();
    inbound.addHeader("Authorization", "Bearer user-jwt");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
    client.consumed(
        UUID.randomUUID(), new ConsumedLinesRequest(UUID.randomUUID(), new ArrayList<>()));
    return seen.get();
  }

  @Test
  @DisplayName("internal cart call inside a request context: X-Internal-Token and no Authorization")
  void carriesInternalTokenAndNoBearer() {
    Request sent = captureCall(true);
    assertThat(sent.headers().get("X-Internal-Token")).containsExactly("secret-token");
    assertThat(sent.headers()).doesNotContainKey("Authorization");
  }

  @Test
  @DisplayName("without the stripper the global interceptor would leak the user bearer")
  void withoutStripperBearerLeaks() {
    Request sent = captureCall(false);
    assertThat(sent.headers().get("Authorization")).containsExactly("Bearer user-jwt");
  }

  @Test
  @DisplayName("the client is wired to the stripper and the token config")
  void clientReferencesBothConfigs() {
    var cfg =
        CartInternalFeignClient.class
            .getAnnotation(org.springframework.cloud.openfeign.FeignClient.class)
            .configuration();
    assertThat(cfg).contains(InternalFeignConfig.class, NoUserTokenFeignConfig.class);
  }
}
