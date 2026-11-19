package com.acme.ecommerce.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.auth.error.ProblemResponseWriter;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.auth.support.RedisTestContainer;
import com.acme.ecommerce.auth.support.TestAuthProperties;
import com.acme.ecommerce.auth.web.RequestDeviceContext;
import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** AC-1 and AC-3: two "instances" (separate Redis connections) share one per-IP allowance. */
class SharedRedisLimiterIT {

  private static final int CAPACITY = 3;
  private static final String PATH = "/api/auth/login";

  private final FixedTimeProvider time = FixedTimeProvider.atEpoch();
  private LettuceConnectionFactory factoryA;
  private LettuceConnectionFactory factoryB;
  private StringRedisTemplate redisA;
  private RateLimitFilter filterA;
  private RateLimitFilter filterB;

  @BeforeEach
  void setUp() {
    factoryA = new LettuceConnectionFactory(RedisTestContainer.host(), RedisTestContainer.port());
    factoryA.afterPropertiesSet();
    factoryB = new LettuceConnectionFactory(RedisTestContainer.host(), RedisTestContainer.port());
    factoryB.afterPropertiesSet();
    redisA = new StringRedisTemplate(factoryA);
    redisA.afterPropertiesSet();
    StringRedisTemplate redisB = new StringRedisTemplate(factoryB);
    redisB.afterPropertiesSet();
    redisA.execute(
        connection -> {
          connection.serverCommands().flushAll();
          return null;
        },
        true);

    var properties = TestAuthProperties.with(TestAuthProperties.rateLimit(CAPACITY, 1000));
    RequestDeviceContext devices = mock(RequestDeviceContext.class);
    when(devices.clientIp(any(HttpServletRequest.class)))
        .thenAnswer(invocation -> ((HttpServletRequest) invocation.getArgument(0)).getRemoteAddr());
    ProblemResponseWriter problems = new ProblemResponseWriter(new ObjectMapper(), time);
    AuthMetrics metrics = mock(AuthMetrics.class);
    filterA =
        new RateLimitFilter(
            properties,
            devices,
            problems,
            metrics,
            new SharedWindowCounter(redisA, time, properties));
    filterB =
        new RateLimitFilter(
            properties,
            devices,
            problems,
            metrics,
            new SharedWindowCounter(redisB, time, properties));
  }

  @AfterEach
  void tearDown() {
    factoryA.destroy();
    factoryB.destroy();
  }

  private MockHttpServletResponse call(RateLimitFilter filter, String ip, MockFilterChain chain)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
    request.setRemoteAddr(ip);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  @DisplayName("C requests alternating across two instances pass; C+1 is 429 AUTH_RATE_LIMITED")
  void sharedAllowance() throws Exception {
    for (int i = 0; i < CAPACITY; i++) {
      MockFilterChain chain = new MockFilterChain();
      MockHttpServletResponse ok = call(i % 2 == 0 ? filterA : filterB, "203.0.113.7", chain);
      assertThat(ok.getStatus()).isEqualTo(200);
      assertThat(chain.getRequest()).isNotNull();
    }

    MockFilterChain blockedChain = new MockFilterChain();
    MockHttpServletResponse blocked =
        call(CAPACITY % 2 == 0 ? filterA : filterB, "203.0.113.7", blockedChain);

    assertThat(blockedChain.getRequest()).isNull();
    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getContentAsString()).contains("\"code\":\"AUTH_RATE_LIMITED\"");
    // Contract pinned: ProblemResponseWriter resets headers, so there is no Retry-After today.
    assertThat(blocked.getHeader("Retry-After")).isNull();
    assertThat(blocked.getHeaderNames()).containsExactly("Content-Type");
    assertThat(blocked.getContentType()).startsWith("application/problem+json");

    // A different source address has its own allowance.
    assertThat(call(filterA, "198.51.100.9", new MockFilterChain()).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("the counter is a Redis key auth:rl:* with a TTL")
  void counterLivesInRedisWithTtl() throws Exception {
    call(filterA, "203.0.113.7", new MockFilterChain());
    call(filterB, "203.0.113.7", new MockFilterChain());

    Set<String> keys = redisA.keys("auth:rl:*");
    assertThat(keys).hasSize(1);
    String key = keys.iterator().next();
    assertThat(key).startsWith("auth:rl:" + PATH + ":203.0.113.7:");
    assertThat(redisA.opsForValue().get(key)).isEqualTo("2");
    assertThat(redisA.getExpire(key)).isBetween(1L, 90L);
  }
}
