package com.acme.ecommerce.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.net.ClientAddress;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * AC-3: the rate limiter's anonymous key and the propagated header come from one implementation.
 * Every input runs through the REAL {@link RateLimitWebFilter} (capturing the Redis key) and the
 * REAL {@link ClientIpPropagationGatewayFilter} (capturing the proxied request) and the two must
 * agree: key suffix == header, header absent iff the key is {@code unknown}.
 */
class GatewayClientIpContractTest {

  private record Case(String name, int hops, String xff, InetSocketAddress peer, String expected) {}

  private static InetSocketAddress peer(String ip) throws Exception {
    return new InetSocketAddress(InetAddress.getByName(ip), 54321);
  }

  private static List<Case> matrix() throws Exception {
    List<Case> c = new ArrayList<>();
    c.add(new Case("N=1 last entry", 1, "9.9.9.9, 203.0.113.7", peer("10.0.0.1"), "203.0.113.7"));
    c.add(
        new Case(
            "N=2 second from right",
            2,
            "6.6.6.6, 203.0.113.7, 10.1.1.1",
            peer("10.0.0.1"),
            "203.0.113.7"));
    c.add(new Case("N=0 ignores XFF", 0, "203.0.113.7", peer("10.0.0.1"), "10.0.0.1"));
    c.add(new Case("too few entries", 3, "1.1.1.1, 2.2.2.2", peer("10.0.0.1"), "10.0.0.1"));
    c.add(new Case("no XFF", 1, null, peer("10.0.0.1"), "10.0.0.1"));
    c.add(new Case("non-literal entry", 1, "1.1.1.1, garbage", peer("10.0.0.1"), "10.0.0.1"));
    c.add(
        new Case("ip:port entry", 1, "1.1.1.1, 203.0.113.7:8080", peer("10.0.0.1"), "203.0.113.7"));
    c.add(
        new Case(
            "bracketed v6",
            1,
            "1.1.1.1, [2001:db8::1]:443",
            peer("10.0.0.1"),
            "2001:db8:0:0:0:0:0:1"));
    c.add(
        new Case(
            "v6 upper case", 1, "1.1.1.1, 2001:DB8::1", peer("10.0.0.1"), "2001:db8:0:0:0:0:0:1"));
    c.add(
        new Case(
            "mapped v4 canonicalised", 1, "1.1.1.1, ::FFFF:1.2.3.4", peer("10.0.0.1"), "1.2.3.4"));
    c.add(
        new Case(
            "loose 127.1 falls back to peer", 1, "1.1.1.1, 127.1", peer("10.0.0.1"), "10.0.0.1"));
    c.add(new Case("loose 1 falls back to peer", 1, "1.1.1.1, 1", peer("10.0.0.1"), "10.0.0.1"));
    c.add(
        new Case("over-long entry", 1, "1.1.1.1, " + "1".repeat(65), peer("10.0.0.1"), "10.0.0.1"));
    c.add(new Case("hostname entry", 1, "1.1.1.1, example.com", peer("10.0.0.1"), "10.0.0.1"));
    c.add(
        new Case(
            "two XFF lines, client line first",
            1,
            "6.6.6.6|10.9.9.9, 203.0.113.7",
            peer("10.0.0.1"),
            "203.0.113.7"));
    c.add(new Case("trailing comma falls back", 1, "1.1.1.1,", peer("10.0.0.1"), "10.0.0.1"));
    c.add(new Case("null peer, no XFF", 1, null, null, null));
    c.add(new Case("null peer, N=0", 0, "1.1.1.1", null, null));
    c.add(
        new Case(
            "unresolved peer",
            0,
            null,
            InetSocketAddress.createUnresolved("nohost.invalid", 80),
            null));
    c.add(
        new Case(
            "null peer, valid XFF still wins", 1, "1.1.1.1, 203.0.113.7", null, "203.0.113.7"));
    return c;
  }

  private static MockServerHttpRequest request(Case c) {
    MockServerHttpRequest.BaseBuilder<?> b = MockServerHttpRequest.get("/api/products");
    if (c.xff() != null) {
      b.header("X-Forwarded-For", c.xff().split("[|]")); // '|' separates field lines
    }
    if (c.peer() != null) {
      b.remoteAddress(c.peer());
    }
    return b.build();
  }

  @SuppressWarnings("unchecked")
  private static String rateLimitKeySuffix(Case c, GatewayClientIp resolver) {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
    AtomicReference<String> key = new AtomicReference<>();
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment(anyString()))
        .thenAnswer(
            inv -> {
              key.set(inv.getArgument(0));
              return Mono.just(1L);
            });
    when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    RateLimitWebFilter filter = new RateLimitWebFilter(redis, null, true, 30, 300, resolver);
    WebFilterChain chain = ex -> Mono.empty();
    filter.filter(MockServerWebExchange.from(request(c)), chain).block();
    String k = key.get();
    assertThat(k).startsWith("rl:r:ip:");
    return k.substring("rl:r:ip:".length(), k.lastIndexOf(':'));
  }

  private static String propagatedHeader(Case c, GatewayClientIp resolver) {
    ClientIpPropagationGatewayFilter filter = new ClientIpPropagationGatewayFilter(resolver);
    AtomicReference<HttpHeaders> seen = new AtomicReference<>();
    GatewayFilterChain chain =
        ex -> {
          seen.set(ex.getRequest().getHeaders());
          return Mono.empty();
        };
    filter.filter(MockServerWebExchange.from(request(c)), chain).block();
    assertThat(seen.get().get(ClientAddress.HEADER))
        .satisfiesAnyOf(v -> assertThat(v).isNull(), v -> assertThat(v).hasSize(1));
    return seen.get().getFirst(ClientAddress.HEADER);
  }

  @Test
  @DisplayName("header value and anonymous rate-limit key agree for the whole AC-1/2 matrix")
  void keyAndHeaderAgree() throws Exception {
    for (Case c : matrix()) {
      GatewayClientIp resolver = new GatewayClientIp(c.hops());
      String suffix = rateLimitKeySuffix(c, resolver);
      String header = propagatedHeader(c, resolver);
      assertThat(header).as(c.name() + " header").isEqualTo(c.expected());
      assertThat(suffix).as(c.name() + " key").isEqualTo(c.expected() == null ? "unknown" : header);
      assertThat(header == null)
          .as(c.name() + " empty iff unknown")
          .isEqualTo("unknown".equals(suffix));
    }
  }

  @Test
  @DisplayName("a client-sent header is replaced, or removed when the peer is unresolvable")
  void clientValueNeverSurvives() throws Exception {
    GatewayClientIp resolver = new GatewayClientIp(0);
    ClientIpPropagationGatewayFilter filter = new ClientIpPropagationGatewayFilter(resolver);
    for (InetSocketAddress p : new InetSocketAddress[] {peer("10.0.0.1"), null}) {
      MockServerHttpRequest.BaseBuilder<?> b =
          MockServerHttpRequest.get("/x").header("x-internal-client-ip", "6.6.6.6");
      if (p != null) {
        b.remoteAddress(p);
      }
      AtomicReference<HttpHeaders> seen = new AtomicReference<>();
      filter
          .filter(
              MockServerWebExchange.from(b.build()),
              ex -> {
                seen.set(ex.getRequest().getHeaders());
                return Mono.empty();
              })
          .block();
      assertThat(seen.get().get(ClientAddress.HEADER))
          .isEqualTo(p == null ? null : List.of("10.0.0.1"));
    }
  }
}
