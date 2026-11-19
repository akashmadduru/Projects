package com.acme.ecommerce.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.core.net.ClientAddress;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class ClientIpPropagationGatewayFilterTest {

  private static HttpHeaders run(int hops, MockServerHttpRequest request) {
    AtomicReference<HttpHeaders> seen = new AtomicReference<>();
    new ClientIpPropagationGatewayFilter(new GatewayClientIp(hops))
        .filter(
            MockServerWebExchange.from(request),
            ex -> {
              seen.set(ex.getRequest().getHeaders());
              return Mono.empty();
            })
        .block();
    return seen.get();
  }

  @Test
  @DisplayName("set replaces repeated and mixed-case client-supplied values")
  void replacesClientValues() throws Exception {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/x")
            .header("X-Internal-Client-Ip", "6.6.6.6")
            .header("x-internal-client-ip", "7.7.7.7")
            .header("X-INTERNAL-CLIENT-IP", "8.8.8.8, 9.9.9.9")
            .header("X-Forwarded-For", "5.5.5.5, 203.0.113.7")
            .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.1"), 1000))
            .build();

    assertThat(run(1, request).get(ClientAddress.HEADER)).containsExactly("203.0.113.7");
  }

  @Test
  @DisplayName("N=0: the header is the request's remote address, not the outbound socket")
  void headerIsTheRequestPeer() throws Exception {
    // The proxied request is built from the inbound request's remote address; whatever socket the
    // gateway later opens to the downstream (127.0.0.1 in the integration test) is irrelevant.
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/x")
            .remoteAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 1000))
            .build();

    assertThat(run(0, request).get(ClientAddress.HEADER)).containsExactly("127.0.0.2");
  }

  @Test
  @DisplayName("no resolvable address: the header is removed, never blank")
  void removedWhenUnknown() {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/x").header("X-Internal-Client-Ip", "6.6.6.6").build();

    assertThat(run(0, request).containsHeader(ClientAddress.HEADER)).isFalse();
  }

  @Test
  @DisplayName("runs after identity propagation and before route resolution")
  void order() {
    ClientIpPropagationGatewayFilter filter =
        new ClientIpPropagationGatewayFilter(new GatewayClientIp(0));
    assertThat(filter.getOrder())
        .isGreaterThan(IdentityPropagationGatewayFilter.ORDER)
        .isLessThan(10000)
        .isEqualTo(ClientIpPropagationGatewayFilter.ORDER);
    assertThat(((Ordered) filter).getOrder()).isEqualTo(1);
    assertThat(List.of(GatewayHeaders.CLIENT_IP)).containsExactly("X-Internal-Client-Ip");
  }
}
