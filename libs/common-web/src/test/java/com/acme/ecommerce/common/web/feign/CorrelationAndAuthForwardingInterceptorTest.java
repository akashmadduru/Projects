package com.acme.ecommerce.common.web.feign;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CorrelationAndAuthForwardingInterceptorTest {

  private final CorrelationAndAuthForwardingInterceptor interceptor =
      new CorrelationAndAuthForwardingInterceptor();

  @AfterEach
  void reset() {
    RequestContextHolder.resetRequestAttributes();
    CorrelationIdHolder.clear();
  }

  @Test
  @DisplayName("relays the caller's bearer token so the downstream service sees the original user")
  void relaysAuthorizationHeader() {
    bindRequestWithAuthorization("Bearer token-abc");
    RequestTemplate template = new RequestTemplate();

    interceptor.apply(template);

    assertThat(template.headers().get("Authorization")).containsExactly("Bearer token-abc");
  }

  @Test
  @DisplayName("propagates the current correlation id")
  void propagatesCorrelationId() {
    CorrelationIdHolder.set("corr-1");
    RequestTemplate template = new RequestTemplate();

    interceptor.apply(template);

    assertThat(template.headers().get(CorrelationIdHolder.HEADER_NAME)).containsExactly("corr-1");
  }

  @Test
  @DisplayName("still emits a correlation id with no inbound request (scheduled job / consumer)")
  void generatesCorrelationIdWithoutRequestContext() {
    RequestTemplate template = new RequestTemplate();

    interceptor.apply(template);

    assertThat(template.headers().get(CorrelationIdHolder.HEADER_NAME)).isNotEmpty();
    // No inbound request means no token to relay - documented limitation.
    assertThat(template.headers()).doesNotContainKey("Authorization");
  }

  @Test
  @DisplayName("does not overwrite an Authorization header the call site set deliberately")
  void doesNotOverwriteExplicitAuthorization() {
    bindRequestWithAuthorization("Bearer user-token");
    RequestTemplate template = new RequestTemplate();
    template.header("Authorization", "Bearer service-token");

    interceptor.apply(template);

    assertThat(template.headers().get("Authorization")).containsExactly("Bearer service-token");
  }

  @Test
  @DisplayName("does not overwrite an explicitly set correlation id")
  void doesNotOverwriteExplicitCorrelationId() {
    CorrelationIdHolder.set("ambient");
    RequestTemplate template = new RequestTemplate();
    template.header(CorrelationIdHolder.HEADER_NAME, "explicit");

    interceptor.apply(template);

    assertThat(template.headers().get(CorrelationIdHolder.HEADER_NAME)).containsExactly("explicit");
  }

  @Test
  @DisplayName("relays the token only to in-cluster targets (TD-SEC-10)")
  void relaysOnlyToInternalTargets() {
    bindRequestWithAuthorization("Bearer user-token");
    for (String url :
        new String[] {
          "http://order-service",
          "http://cart-service:8083",
          "http://user-service.ecommerce.svc.cluster.local:8085",
          "http://localhost:8082",
          "http://10.0.3.7:8080"
        }) {
      RequestTemplate template = new RequestTemplate();
      template.feignTarget(new feign.Target.HardCodedTarget<>(Object.class, url));
      interceptor.apply(template);
      assertThat(template.headers().get("Authorization"))
          .as(url)
          .containsExactly("Bearer user-token");
    }
    for (String url : new String[] {"https://api.example.com", "http://8.8.8.8"}) {
      RequestTemplate template = new RequestTemplate();
      template.feignTarget(new feign.Target.HardCodedTarget<>(Object.class, url));
      interceptor.apply(template);
      assertThat(template.headers()).as(url).doesNotContainKey("Authorization");
    }
  }

  private static void bindRequestWithAuthorization(String authorization) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", authorization);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }
}
